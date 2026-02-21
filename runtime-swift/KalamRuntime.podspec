Pod::Spec.new do |s|
  s.name         = 'KalamRuntime'
  s.version      = '0.1.0'
  s.summary      = 'Transport-agnostic RPC runtime for Swift'
  s.homepage     = 'https://github.com/alexshawnee/kalam'
  s.license      = { :type => 'MIT' }
  s.author       = 'Kalam'
  s.source       = { :git => 'https://github.com/alexshawnee/kalam.git', :tag => s.version.to_s }

  s.ios.deployment_target = '13.0'
  s.osx.deployment_target = '10.15'

  s.swift_version = '5.7'
  s.source_files  = 'Sources/**/*.swift'

  s.dependency 'SwiftProtobuf', '~> 1.25'
end
