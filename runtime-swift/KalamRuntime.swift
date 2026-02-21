import Foundation
#if canImport(Darwin)
import Darwin
#endif

// MARK: - Constants

private let protocolVersion: UInt8 = 1
private let frameTypeUnary: UInt8 = 0
private let frameTypeStreamChunk: UInt8 = 1
private let frameTypeStreamEnd: UInt8 = 2
private let frameTypeError: UInt8 = 3
private let headerSize = 14

// MARK: - Frame

struct Frame {
    let version: UInt8
    let requestId: UInt32
    let frameType: UInt8
    let method: String
    let payload: Data
}

func encodeFrame(requestId: UInt32, frameType: UInt8, method: String, payload: Data) -> Data {
    let methodBytes = Array(method.utf8)
    let total = headerSize + methodBytes.count + payload.count
    var buffer = Data(capacity: total)

    buffer.append(protocolVersion)
    var rid = requestId.bigEndian
    buffer.append(Data(bytes: &rid, count: 4))
    buffer.append(frameType)
    var mlen = UInt32(methodBytes.count).bigEndian
    buffer.append(Data(bytes: &mlen, count: 4))
    buffer.append(contentsOf: methodBytes)
    var plen = UInt32(payload.count).bigEndian
    buffer.append(Data(bytes: &plen, count: 4))
    buffer.append(payload)

    return buffer
}

// MARK: - FrameReader

class FrameReader {
    private var buffer = Data()
    private var consumed = 0

    func add(_ chunk: Data) {
        buffer.append(chunk)
    }

    func tryReadFrame() -> Frame? {
        let available = buffer.count - consumed

        guard available >= headerSize else { return nil }

        let start = consumed
        let version = buffer[start]
        let requestId = buffer.readUInt32(at: start + 1)
        let frameType = buffer[start + 5]
        let methodLen = Int(buffer.readUInt32(at: start + 6))

        let neededForPayloadLen = headerSize + methodLen
        guard available >= neededForPayloadLen else { return nil }

        let payloadLen = Int(buffer.readUInt32(at: start + 10 + methodLen))
        let totalLen = neededForPayloadLen + payloadLen
        guard available >= totalLen else { return nil }

        let methodStart = start + 10
        let method = String(data: buffer[methodStart..<(methodStart + methodLen)], encoding: .utf8) ?? ""
        let payloadStart = start + neededForPayloadLen
        let payload = Data(buffer[payloadStart..<(payloadStart + payloadLen)])

        consumed += totalLen
        return Frame(version: version, requestId: requestId, frameType: frameType, method: method, payload: payload)
    }

    func compact() {
        guard consumed > 0 else { return }
        buffer = Data(buffer[consumed...])
        consumed = 0
    }
}

private extension Data {
    func readUInt32(at offset: Int) -> UInt32 {
        var value: UInt32 = 0
        _ = Swift.withUnsafeMutableBytes(of: &value) { dest in
            self.copyBytes(to: dest, from: offset..<(offset + 4))
        }
        return UInt32(bigEndian: value)
    }
}

// MARK: - Exception

struct KalamException: Error, CustomStringConvertible {
    let method: String
    let message: String

    var description: String { "KalamException(\(method)): \(message)" }
}

private func checkError(_ frame: Frame) throws {
    if frame.frameType == frameTypeError {
        throw KalamException(method: frame.method, message: String(data: frame.payload, encoding: .utf8) ?? "")
    }
}

// MARK: - Client

final class Kalam {
    static let shared = Kalam()
    private init() {}

    private var socketPath: String?
    private var fd: Int32 = -1
    private var nextRequestId: UInt32 = 1
    private let lock = NSLock()
    private let readQueue = DispatchQueue(label: "com.kalam.read", qos: .utility)
    private var pendingCalls: [UInt32: (Result<Frame, Error>) -> Void] = [:]
    private var pendingStreams: [UInt32: StreamCallbacks] = [:]
    private var reading = false

    private struct StreamCallbacks {
        let onChunk: (Frame) -> Void
        let onEnd: (Error?) -> Void
    }

    func useSockets(_ path: String) {
        disconnect()
        socketPath = path
    }

    private func connect() throws {
        guard let path = socketPath else {
            fatalError("Kalam not initialized. Call useSockets() first.")
        }

        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { throw POSIXError(.init(rawValue: errno) ?? .ENOENT) }

        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let pathBytes = path.utf8CString
        withUnsafeMutablePointer(to: &addr.sun_path) { ptr in
            ptr.withMemoryRebound(to: CChar.self, capacity: 104) { dest in
                for (i, byte) in pathBytes.enumerated() {
                    dest[i] = byte
                }
            }
        }

        let addrLen = socklen_t(MemoryLayout<sockaddr_un>.size)
        let result = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockPtr in
                Darwin.connect(fd, sockPtr, addrLen)
            }
        }
        guard result == 0 else {
            Darwin.close(fd)
            throw POSIXError(.init(rawValue: errno) ?? .ECONNREFUSED)
        }

        self.fd = fd
        startReadLoop()
    }

    private func startReadLoop() {
        let fd = self.fd
        reading = true
        readQueue.async { [weak self] in
            let reader = FrameReader()
            let buf = UnsafeMutablePointer<UInt8>.allocate(capacity: 4096)
            defer { buf.deallocate() }

            while self?.reading == true {
                let n = Darwin.read(fd, buf, 4096)
                if n <= 0 { break }
                reader.add(Data(bytes: buf, count: n))

                while let frame = reader.tryReadFrame() {
                    reader.compact()
                    self?.dispatch(frame)
                }
            }
            self?.disconnect()
        }
    }

    private func dispatch(_ frame: Frame) {
        lock.lock()
        let id = frame.requestId

        if let completion = pendingCalls.removeValue(forKey: id) {
            lock.unlock()
            completion(.success(frame))
            return
        }

        if let callbacks = pendingStreams[id] {
            if frame.frameType == frameTypeStreamEnd {
                pendingStreams.removeValue(forKey: id)
                lock.unlock()
                callbacks.onEnd(nil)
            } else if frame.frameType == frameTypeError {
                pendingStreams.removeValue(forKey: id)
                lock.unlock()
                let err = KalamException(
                    method: frame.method,
                    message: String(data: frame.payload, encoding: .utf8) ?? ""
                )
                callbacks.onEnd(err)
            } else {
                lock.unlock()
                callbacks.onChunk(frame)
            }
            return
        }

        lock.unlock()
    }

    private func ensureConnected() throws {
        if fd < 0 { try connect() }
    }

    private func sendFrame(_ data: Data) {
        data.withUnsafeBytes { ptr in
            _ = Darwin.write(self.fd, ptr.baseAddress!, data.count)
        }
    }

    // MARK: Callback API (iOS 13+)

    func call(_ method: String, _ payload: Data, completion: @escaping (Result<Data, Error>) -> Void) {
        do {
            try ensureConnected()
        } catch {
            completion(.failure(error))
            return
        }

        lock.lock()
        let requestId = nextRequestId
        nextRequestId += 1
        lock.unlock()

        let frameData = encodeFrame(requestId: requestId, frameType: frameTypeUnary, method: method, payload: payload)

        lock.lock()
        pendingCalls[requestId] = { result in
            switch result {
            case .success(let frame):
                do {
                    try checkError(frame)
                    completion(.success(frame.payload))
                } catch {
                    completion(.failure(error))
                }
            case .failure(let error):
                completion(.failure(error))
            }
        }
        lock.unlock()
        sendFrame(frameData)
    }

    func stream(_ method: String, _ payload: Data, onChunk: @escaping (Data) -> Void, onEnd: @escaping (Error?) -> Void) {
        do {
            try ensureConnected()
        } catch {
            onEnd(error)
            return
        }

        lock.lock()
        let requestId = nextRequestId
        nextRequestId += 1
        lock.unlock()

        let frameData = encodeFrame(requestId: requestId, frameType: frameTypeUnary, method: method, payload: payload)

        lock.lock()
        pendingStreams[requestId] = StreamCallbacks(
            onChunk: { frame in
                do {
                    try checkError(frame)
                    onChunk(frame.payload)
                } catch {
                    onEnd(error)
                }
            },
            onEnd: onEnd
        )
        lock.unlock()
        sendFrame(frameData)
    }

    func disconnect() {
        reading = false
        if fd >= 0 {
            Darwin.close(fd)
            fd = -1
        }
        lock.lock()
        let calls = pendingCalls
        let streams = pendingStreams
        pendingCalls.removeAll()
        pendingStreams.removeAll()
        lock.unlock()

        for (_, completion) in calls {
            completion(.failure(KalamException(method: "", message: "Connection lost")))
        }
        for (_, callbacks) in streams {
            callbacks.onEnd(KalamException(method: "", message: "Connection lost"))
        }
    }
}

// MARK: - Server

protocol ServiceRouter {
    func handle(method: String, payload: Data, sink: ResponseSink)
}

final class ResponseSink {
    private let fd: Int32
    private let requestId: UInt32
    private let method: String

    init(fd: Int32, requestId: UInt32, method: String) {
        self.fd = fd
        self.requestId = requestId
        self.method = method
    }

    func sendUnary(_ payload: Data) {
        send(frameType: frameTypeUnary, payload: payload)
    }

    func sendChunk(_ payload: Data) {
        send(frameType: frameTypeStreamChunk, payload: payload)
    }

    func sendEnd() {
        send(frameType: frameTypeStreamEnd, payload: Data())
    }

    func sendError(_ message: String) {
        send(frameType: frameTypeError, payload: Data(message.utf8))
    }

    private func send(frameType: UInt8, payload: Data) {
        let data = encodeFrame(requestId: requestId, frameType: frameType, method: method, payload: payload)
        data.withUnsafeBytes { ptr in
            _ = Darwin.write(fd, ptr.baseAddress!, data.count)
        }
    }
}

final class KalamServer {
    static let shared = KalamServer()
    private init() {}

    private var serverFd: Int32 = -1
    private let acceptQueue = DispatchQueue(label: "com.kalam.accept", qos: .utility)
    private let clientQueue = DispatchQueue(label: "com.kalam.clients", qos: .utility, attributes: .concurrent)
    private var listening = false

    func serve(_ path: String, _ router: ServiceRouter) {
        unlink(path)

        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { fatalError("Failed to create socket") }

        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let pathBytes = path.utf8CString
        withUnsafeMutablePointer(to: &addr.sun_path) { ptr in
            ptr.withMemoryRebound(to: CChar.self, capacity: 104) { dest in
                for (i, byte) in pathBytes.enumerated() {
                    dest[i] = byte
                }
            }
        }

        let addrLen = socklen_t(MemoryLayout<sockaddr_un>.size)
        _ = withUnsafePointer(to: &addr) { ptr in
            ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockPtr in
                Darwin.bind(fd, sockPtr, addrLen)
            }
        }

        Darwin.listen(fd, 5)
        serverFd = fd
        listening = true

        print("Kalam server listening on \(path)")

        acceptQueue.async { [weak self] in
            while self?.listening == true {
                let clientFd = Darwin.accept(fd, nil, nil)
                if clientFd < 0 { break }
                self?.clientQueue.async {
                    self?.handleClient(clientFd, router)
                }
            }
        }
    }

    private func handleClient(_ clientFd: Int32, _ router: ServiceRouter) {
        let reader = FrameReader()
        let buf = UnsafeMutablePointer<UInt8>.allocate(capacity: 4096)
        defer {
            buf.deallocate()
            Darwin.close(clientFd)
        }

        while true {
            let n = Darwin.read(clientFd, buf, 4096)
            if n <= 0 { break }
            reader.add(Data(bytes: buf, count: n))

            while let request = reader.tryReadFrame() {
                reader.compact()
                let sink = ResponseSink(fd: clientFd, requestId: request.requestId, method: request.method)
                router.handle(method: request.method, payload: request.payload, sink: sink)
            }
        }
    }

    func close() {
        listening = false
        if serverFd >= 0 {
            Darwin.close(serverFd)
            serverFd = -1
        }
    }
}
