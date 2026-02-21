package main

import (
	"embed"
	"fmt"
	"strings"
	"text/template"
	"unicode"

	"google.golang.org/protobuf/compiler/protogen"
	"google.golang.org/protobuf/reflect/protoreflect"
)

//go:embed templates/*
var templateFS embed.FS

//go:embed runtime/*
var runtimeFS embed.FS

func main() {
	protogen.Options{}.Run(func(gen *protogen.Plugin) error {
		// Parse lang parameter (default: dart)
		lang := "dart"
		for _, p := range strings.Split(gen.Request.GetParameter(), ",") {
			p = strings.TrimSpace(p)
			if strings.HasPrefix(p, "lang=") {
				lang = strings.TrimPrefix(p, "lang=")
			}
		}

		tmplFile := "templates/" + lang + ".tmpl"
		tmpl, err := template.New(lang + ".tmpl").Funcs(template.FuncMap{
			"lowerFirst": lowerFirst,
		}).ParseFS(templateFS, tmplFile)
		if err != nil {
			return fmt.Errorf("parse template %s: %w", tmplFile, err)
		}

		for _, f := range gen.Files {
			if !f.Generate {
				continue
			}

			data := buildFileData(f, lang)

			var ext string
			switch lang {
			case "kotlin":
				ext = ".klm.kt"
			case "swift":
				ext = ".klm.swift"
			default:
				ext = ".klm.dart"
			}

			fileName := strings.TrimSuffix(f.Desc.Path(), ".proto") + ext
			g := gen.NewGeneratedFile(fileName, "")

			if err := tmpl.Execute(g, data); err != nil {
				return fmt.Errorf("execute template for %s: %w", f.Desc.Path(), err)
			}
		}

		// Copy runtime files
		var runtimeFile, runtimeOut string
		switch lang {
		case "dart":
			runtimeFile = "runtime/kalam.dart"
			runtimeOut = "kalam.dart"
		case "swift":
			runtimeFile = "runtime/kalam.swift"
			runtimeOut = "kalam.swift"
		}
		if runtimeFile != "" {
			runtimeData, err := runtimeFS.ReadFile(runtimeFile)
			if err != nil {
				return fmt.Errorf("read %s: %w", runtimeFile, err)
			}
			rt := gen.NewGeneratedFile(runtimeOut, "")
			rt.P(string(runtimeData))
		}

		return nil
	})
}

// ── Data types ─────────────────────────────────────────────────────────

type FileData struct {
	FileName    string
	ProtoName   string
	PackageName string
	Enums       []Enum
	Messages    []Message
	Services    []Service
}

type Enum struct {
	Name   string
	Values []EnumValue
}

type EnumValue struct {
	Name   string
	Number int32
}

type Message struct {
	Name   string
	Fields []Field
}

type Field struct {
	Name         string
	KotlinType   string
	ProtoNumber  int
	DefaultValue string
}

type Service struct {
	Name    string
	Prefix  string
	Methods []Method
}

type Method struct {
	Name           string
	MethodName     string
	Input          string
	Output         string
	ServerStreaming bool
}

// ── Build data ─────────────────────────────────────────────────────────

func buildFileData(f *protogen.File, lang string) FileData {
	packageName := string(f.Desc.Package())
	if lang == "kotlin" && packageName == "" {
		packageName = "generated"
	}

	data := FileData{
		FileName:    f.Desc.Path(),
		ProtoName:   strings.TrimSuffix(f.Desc.Path(), ".proto"),
		PackageName: packageName,
	}

	// Enums
	if lang == "kotlin" {
		for _, e := range f.Enums {
			data.Enums = append(data.Enums, buildEnum(e))
		}

		for _, m := range f.Messages {
			data.Messages = append(data.Messages, buildMessage(m, lang))
		}
	}

	// Services
	typePrefix := ""
	if lang == "swift" {
		typePrefix = swiftTypePrefix(packageName)
	}

	for _, svc := range f.Services {
		var methods []Method
		for _, m := range svc.Methods {
			methods = append(methods, Method{
				Name:           string(m.Desc.Name()),
				MethodName:     lowerFirst(string(m.Desc.Name())),
				Input:          typePrefix + string(m.Input.Desc.Name()),
				Output:         typePrefix + string(m.Output.Desc.Name()),
				ServerStreaming: m.Desc.IsStreamingServer(),
			})
		}

		data.Services = append(data.Services, Service{
			Name:    string(svc.Desc.Name()),
			Prefix:  string(svc.Desc.Name()) + "/",
			Methods: methods,
		})
	}

	return data
}

func buildEnum(e *protogen.Enum) Enum {
	var values []EnumValue
	for _, v := range e.Values {
		values = append(values, EnumValue{
			Name:   string(v.Desc.Name()),
			Number: int32(v.Desc.Number()),
		})
	}
	return Enum{
		Name:   string(e.Desc.Name()),
		Values: values,
	}
}

func buildMessage(m *protogen.Message, lang string) Message {
	var fields []Field
	for _, fd := range m.Fields {
		fields = append(fields, Field{
			Name:         lowerCamel(string(fd.Desc.Name())),
			KotlinType:   kotlinType(fd, lang),
			ProtoNumber:  int(fd.Desc.Number()),
			DefaultValue: kotlinDefault(fd),
		})
	}
	return Message{
		Name:   string(m.Desc.Name()),
		Fields: fields,
	}
}

// ── Type mapping ───────────────────────────────────────────────────────

func kotlinType(fd *protogen.Field, lang string) string {
	if fd.Desc.IsList() {
		return "List<" + kotlinScalarType(fd, lang) + ">"
	}
	return kotlinScalarType(fd, lang)
}

func kotlinScalarType(fd *protogen.Field, lang string) string {
	switch fd.Desc.Kind() {
	case protoreflect.BoolKind:
		return "Boolean"
	case protoreflect.Int32Kind, protoreflect.Sint32Kind, protoreflect.Sfixed32Kind:
		return "Int"
	case protoreflect.Int64Kind, protoreflect.Sint64Kind, protoreflect.Sfixed64Kind:
		return "Long"
	case protoreflect.Uint32Kind, protoreflect.Fixed32Kind:
		return "UInt"
	case protoreflect.Uint64Kind, protoreflect.Fixed64Kind:
		return "ULong"
	case protoreflect.FloatKind:
		return "Float"
	case protoreflect.DoubleKind:
		return "Double"
	case protoreflect.StringKind:
		return "String"
	case protoreflect.BytesKind:
		return "ByteArray"
	case protoreflect.EnumKind:
		return string(fd.Enum.Desc.Name())
	case protoreflect.MessageKind, protoreflect.GroupKind:
		return string(fd.Message.Desc.Name())
	default:
		return "Any"
	}
}

func kotlinDefault(fd *protogen.Field) string {
	if fd.Desc.IsList() {
		return "emptyList()"
	}
	switch fd.Desc.Kind() {
	case protoreflect.BoolKind:
		return "false"
	case protoreflect.Int32Kind, protoreflect.Sint32Kind, protoreflect.Sfixed32Kind:
		return "0"
	case protoreflect.Int64Kind, protoreflect.Sint64Kind, protoreflect.Sfixed64Kind:
		return "0L"
	case protoreflect.Uint32Kind, protoreflect.Fixed32Kind:
		return "0u"
	case protoreflect.Uint64Kind, protoreflect.Fixed64Kind:
		return "0uL"
	case protoreflect.FloatKind:
		return "0f"
	case protoreflect.DoubleKind:
		return "0.0"
	case protoreflect.StringKind:
		return "\"\""
	case protoreflect.BytesKind:
		return "byteArrayOf()"
	case protoreflect.EnumKind:
		// First enum value as default
		if len(fd.Enum.Values) > 0 {
			return string(fd.Enum.Desc.Name()) + "." + string(fd.Enum.Values[0].Desc.Name())
		}
		return string(fd.Enum.Desc.Name()) + ".UNKNOWN"
	case protoreflect.MessageKind, protoreflect.GroupKind:
		return string(fd.Message.Desc.Name()) + "()"
	default:
		return "null"
	}
}

// ── Helpers ────────────────────────────────────────────────────────────

func swiftTypePrefix(pkg string) string {
	if pkg == "" {
		return ""
	}
	parts := strings.Split(pkg, ".")
	for i, p := range parts {
		if len(p) > 0 {
			parts[i] = strings.ToUpper(p[:1]) + p[1:]
		}
	}
	return strings.Join(parts, "_") + "_"
}

func lowerFirst(s string) string {
	if s == "" {
		return s
	}
	r := []rune(s)
	r[0] = unicode.ToLower(r[0])
	return string(r)
}

// lowerCamel converts snake_case to camelCase
func lowerCamel(s string) string {
	parts := strings.Split(s, "_")
	for i := 1; i < len(parts); i++ {
		if len(parts[i]) > 0 {
			r := []rune(parts[i])
			r[0] = unicode.ToUpper(r[0])
			parts[i] = string(r)
		}
	}
	return strings.Join(parts, "")
}

func toSnakeCase(s string) string {
	var result []rune
	for i, r := range s {
		if unicode.IsUpper(r) && i > 0 {
			result = append(result, '_')
		}
		result = append(result, unicode.ToLower(r))
	}
	return string(result)
}
