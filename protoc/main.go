package main

import (
	"embed"
	"fmt"
	"strings"
	"text/template"
	"unicode"

	"google.golang.org/protobuf/compiler/protogen"
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

			for _, svc := range f.Services {
				data := buildServiceData(f, svc, lang)

				var ext string
				switch lang {
				case "kotlin":
					ext = ".klm.kt"
				default:
					ext = ".klm.dart"
				}

				fileName := strings.TrimSuffix(f.Desc.Path(), ".proto") + ext
				g := gen.NewGeneratedFile(fileName, "")

				if err := tmpl.Execute(g, data); err != nil {
					return fmt.Errorf("execute template for %s: %w", svc.GoName, err)
				}
			}
		}

		// Copy runtime files for Dart
		if lang == "dart" {
			transportDart, err := runtimeFS.ReadFile("runtime/kalam.dart")
			if err != nil {
				return fmt.Errorf("read runtime/kalam.dart: %w", err)
			}
			rt := gen.NewGeneratedFile("kalam.dart", "")
			rt.P(string(transportDart))
		}

		return nil
	})
}

type ServiceData struct {
	FileName      string
	ProtoName     string
	PackageName   string
	ServicePrefix string
	Services      []Service
}

type Service struct {
	Name    string
	Methods []Method
}

type Method struct {
	Name           string
	MethodName     string
	Input          string
	Output         string
	ServerStreaming bool
}

func buildServiceData(f *protogen.File, svc *protogen.Service, lang string) ServiceData {
	var methods []Method
	for _, m := range svc.Methods {
		methods = append(methods, Method{
			Name:           string(m.Desc.Name()),
			MethodName:     lowerFirst(string(m.Desc.Name())),
			Input:          string(m.Input.Desc.Name()),
			Output:         string(m.Output.Desc.Name()),
			ServerStreaming: m.Desc.IsStreamingServer(),
		})
	}

	packageName := string(f.Desc.Package())
	if lang == "kotlin" && packageName == "" {
		packageName = "generated"
	}

	return ServiceData{
		FileName:      f.Desc.Path(),
		ProtoName:     strings.TrimSuffix(f.Desc.Path(), ".proto"),
		PackageName:   packageName,
		ServicePrefix: string(svc.Desc.Name()) + "/",
		Services: []Service{
			{
				Name:    string(svc.Desc.Name()),
				Methods: methods,
			},
		},
	}
}

func lowerFirst(s string) string {
	if s == "" {
		return s
	}
	r := []rune(s)
	r[0] = unicode.ToLower(r[0])
	return string(r)
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
