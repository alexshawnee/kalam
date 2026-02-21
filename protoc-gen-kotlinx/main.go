package main

import (
	"embed"
	"fmt"
	"strings"
	"text/template"

	"google.golang.org/protobuf/compiler/protogen"
	"google.golang.org/protobuf/reflect/protoreflect"
)

//go:embed templates/*
var templateFS embed.FS

func main() {
	protogen.Options{}.Run(func(p *protogen.Plugin) error {
		tmpl, err := template.New("kotlinx.tmpl").ParseFS(templateFS, "templates/kotlinx.tmpl")
		if err != nil {
			return fmt.Errorf("parse template: %w", err)
		}

		for _, f := range p.Files {
			if !f.Generate {
				continue
			}

			data := buildFileData(f)
			if len(data.Enums) == 0 && len(data.Messages) == 0 {
				continue
			}

			fileName := strings.TrimSuffix(f.Desc.Path(), ".proto") + ".kt"
			g := p.NewGeneratedFile(fileName, "")

			if err := tmpl.Execute(g, data); err != nil {
				return fmt.Errorf("execute template for %s: %w", f.Desc.Path(), err)
			}
		}

		return nil
	})
}

// ── Data ──────────────────────────────────────────────────────────────

type fileData struct {
	FileName    string
	PackageName string
	Enums       []enum_
	Messages    []message
}

type enum_ struct {
	Name   string
	Values []enumValue
}

type enumValue struct {
	Name   string
	Number int32
}

type message struct {
	Name   string
	Fields []field
}

type field struct {
	Name         string
	Type         string
	ProtoNumber  int
	DefaultValue string
}

func buildFileData(f *protogen.File) fileData {
	pkg := string(f.Desc.Package())
	if pkg == "" {
		pkg = "generated"
	}

	data := fileData{
		FileName:    f.Desc.Path(),
		PackageName: pkg,
	}

	for _, e := range f.Enums {
		var values []enumValue
		for _, v := range e.Values {
			values = append(values, enumValue{
				Name:   string(v.Desc.Name()),
				Number: int32(v.Desc.Number()),
			})
		}
		data.Enums = append(data.Enums, enum_{Name: string(e.Desc.Name()), Values: values})
	}

	for _, m := range f.Messages {
		var fields []field
		for _, fd := range m.Fields {
			fields = append(fields, field{
				Name:         lowerCamel(string(fd.Desc.Name())),
				Type:         kotlinType(fd),
				ProtoNumber:  int(fd.Desc.Number()),
				DefaultValue: kotlinDefault(fd),
			})
		}
		data.Messages = append(data.Messages, message{Name: string(m.Desc.Name()), Fields: fields})
	}

	return data
}

// ── Type mapping ──────────────────────────────────────────────────────

func kotlinType(fd *protogen.Field) string {
	if fd.Desc.IsList() {
		return "List<" + scalarType(fd) + ">"
	}
	return scalarType(fd)
}

func scalarType(fd *protogen.Field) string {
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

func lowerCamel(s string) string {
	parts := strings.Split(s, "_")
	for i := 1; i < len(parts); i++ {
		if len(parts[i]) > 0 {
			r := []rune(parts[i])
			r[0] = 'A' + (r[0] - 'a')
			parts[i] = string(r)
		}
	}
	return strings.Join(parts, "")
}
