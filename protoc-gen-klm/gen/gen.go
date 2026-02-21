package gen

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

// ── Run ───────────────────────────────────────────────────────────────

func Run(lang string) {
	ext := map[string]string{
		"kotlin": ".klm.kt",
		"swift":  ".klm.swift",
		"dart":   ".klm.dart",
	}[lang]

	tmplFile := "templates/" + lang + ".tmpl"

	protogen.Options{}.Run(func(p *protogen.Plugin) error {
		tmpl, err := template.New(lang + ".tmpl").Funcs(template.FuncMap{
			"lowerFirst": LowerFirst,
		}).ParseFS(templateFS, tmplFile)
		if err != nil {
			return fmt.Errorf("parse template %s: %w", tmplFile, err)
		}

		for _, f := range p.Files {
			if !f.Generate {
				continue
			}

			data := BuildFileData(f, lang)
			fileName := strings.TrimSuffix(f.Desc.Path(), ".proto") + ext
			g := p.NewGeneratedFile(fileName, "")

			if err := tmpl.Execute(g, data); err != nil {
				return fmt.Errorf("execute template for %s: %w", f.Desc.Path(), err)
			}
		}

		return nil
	})
}

// ── Build data ─────────────────────────────────────────────────────────

func BuildFileData(f *protogen.File, lang string) FileData {
	packageName := string(f.Desc.Package())
	if lang == "kotlin" && packageName == "" {
		packageName = "generated"
	}

	data := FileData{
		FileName:    f.Desc.Path(),
		ProtoName:   strings.TrimSuffix(f.Desc.Path(), ".proto"),
		PackageName: packageName,
	}

	typePrefix := ""
	if lang == "swift" {
		typePrefix = SwiftTypePrefix(packageName)
	}

	for _, svc := range f.Services {
		var methods []Method
		for _, m := range svc.Methods {
			methods = append(methods, Method{
				Name:           string(m.Desc.Name()),
				MethodName:     LowerFirst(string(m.Desc.Name())),
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

func BuildEnum(e *protogen.Enum) Enum {
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

func BuildMessage(m *protogen.Message) Message {
	var fields []Field
	for _, fd := range m.Fields {
		fields = append(fields, Field{
			Name:         LowerCamel(string(fd.Desc.Name())),
			KotlinType:   KotlinType(fd),
			ProtoNumber:  int(fd.Desc.Number()),
			DefaultValue: KotlinDefault(fd),
		})
	}
	return Message{
		Name:   string(m.Desc.Name()),
		Fields: fields,
	}
}

// ── Type mapping ───────────────────────────────────────────────────────

func KotlinType(fd *protogen.Field) string {
	if fd.Desc.IsList() {
		return "List<" + KotlinScalarType(fd) + ">"
	}
	return KotlinScalarType(fd)
}

func KotlinScalarType(fd *protogen.Field) string {
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

func KotlinDefault(fd *protogen.Field) string {
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

func SwiftTypePrefix(pkg string) string {
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

func LowerFirst(s string) string {
	if s == "" {
		return s
	}
	r := []rune(s)
	r[0] = unicode.ToLower(r[0])
	return string(r)
}

func LowerCamel(s string) string {
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
