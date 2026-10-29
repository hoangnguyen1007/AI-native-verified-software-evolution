// Native Go 1.24 parser/type checker. Imported export data is supplied and hash-checked;
// no go command, target lifecycle, cgo hook, module downloader or application is invoked.
package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"go/ast"
	"go/build"
	"go/importer"
	"go/parser"
	"go/scanner"
	"go/token"
	"go/types"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
)

type File struct{ Path, Text, Sha256, Role string }
type Export struct {
	Path, Sha256, Origin string
	Size                 int64
}
type Request struct {
	Schema, Language, Dialect, SourceRoot, PackagePath, Goarch, Goos string
	Tags                                                             []string
	CgoEnabled                                                       bool
	Files                                                            []File
	Exports                                                          map[string]Export
}
type Site struct {
	Document, Category, Role string
	Start, End, Ordinal      int
	Node                     ast.Node   `json:"-"`
	Parents                  []ast.Node `json:"-"`
}
type Symbol struct {
	Owner, Signature, Name, Kind, Origin, Document string
	Start, End                                     int
}
type Fact struct {
	Status, Reason, Type, Dispatch string
	CallerStatus, CallerReason     string
	Symbols                        []Symbol
	Callee                         *Symbol `json:",omitempty"`
	Caller                         *Symbol `json:",omitempty"`
	Access                         string
}
type Diagnostic struct{ Document, Severity, Code, Message string }
type Projection struct {
	Schema, Compiler  string
	Census            []Site
	Facts             map[string]Fact
	Diagnostics       []Diagnostic
	Traversed, Failed []string
}

func hash(raw []byte) string {
	sum := sha256.Sum256(raw)
	return "sha256:" + hex.EncodeToString(sum[:])
}
func logical(path string) bool {
	if path == "" || strings.ContainsAny(path, "\\:\x00") || strings.HasPrefix(path, "/") {
		return false
	}
	for _, part := range strings.Split(path, "/") {
		if part == "" || part == "." || part == ".." {
			return false
		}
	}
	return true
}
func must(err error) {
	if err != nil {
		panic(err)
	}
}
func main() {
	if len(os.Args) != 2 || runtime.Version() != "go1.24.0" {
		panic("GO_NATIVE_TUPLE_MISMATCH")
	}
	raw, err := os.ReadFile(os.Args[1])
	must(err)
	var request Request
	must(json.Unmarshal(raw, &request))
	if request.Schema != "native-go-request:m8.4-v1" || request.Language != "go" || request.Dialect != "1.24" || request.PackagePath == "" || request.Goos == "" || len(request.Files) == 0 {
		panic("GO_REQUEST_CONTEXT")
	}
	root, err := filepath.Abs(request.SourceRoot)
	must(err)
	real, err := filepath.EvalSymlinks(root)
	must(err)
	if real != root {
		panic("GO_SOURCE_ROOT_ALIAS")
	}
	fset := token.NewFileSet()
	trees := []*ast.File{}
	paths := map[string]string{}
	sources := map[string]File{}
	definitions := map[types.Object]ast.Node{}
	fieldOwners := map[*types.Var]string{}
	methodOwners := map[*types.Func]string{}
	buildContext := build.Default
	buildContext.GOOS = request.Goos
	buildContext.GOARCH = request.Goarch
	buildContext.CgoEnabled = request.CgoEnabled
	buildContext.BuildTags = append([]string{}, request.Tags...)
	buildContext.ReleaseTags = append([]string{}, build.Default.ReleaseTags...)
	output := Projection{Schema: "native-go-projection:m8.4-v1", Compiler: runtime.Version(), Census: []Site{}, Facts: map[string]Fact{}, Diagnostics: []Diagnostic{}, Traversed: []string{}, Failed: []string{}}
	sort.Slice(request.Files, func(i, j int) bool { return request.Files[i].Path < request.Files[j].Path })
	for _, file := range request.Files {
		if !logical(file.Path) || hash([]byte(file.Text)) != file.Sha256 {
			panic("GO_SOURCE_HASH_OR_PATH")
		}
		if _, found := sources[file.Path]; found {
			panic("GO_DUPLICATE_SOURCE")
		}
		physical := filepath.Join(root, filepath.FromSlash(file.Path))
		actual, err := filepath.EvalSymlinks(physical)
		must(err)
		if actual != physical {
			panic("GO_SOURCE_ALIAS")
		}
		bytes, err := os.ReadFile(physical)
		must(err)
		if string(bytes) != file.Text {
			panic("GO_SOURCE_BYTES")
		}
		sources[file.Path] = file
		paths[physical] = file.Path
		tree, failure := parser.ParseFile(fset, physical, bytes, parser.AllErrors|parser.ParseComments)
		selected, selectionError := buildContext.MatchFile(filepath.Dir(physical), filepath.Base(physical))
		if selectionError != nil || !selected {
			output.Failed = append(output.Failed, file.Path)
			output.Diagnostics = append(output.Diagnostics, Diagnostic{file.Path, "ERROR", "GO_SELECTED_CONTEXT_MISMATCH", "Captured selected source does not satisfy the native GOOS/GOARCH/build-tag predicate"})
		}
		if failure != nil {
			output.Failed = append(output.Failed, file.Path)
			if list, ok := failure.(scanner.ErrorList); ok {
				for _, item := range list {
					output.Diagnostics = append(output.Diagnostics, Diagnostic{file.Path, "ERROR", "GO_PARSE", item.Msg})
				}
			} else {
				output.Diagnostics = append(output.Diagnostics, Diagnostic{file.Path, "ERROR", "GO_PARSE", "Native parser failed"})
			}
		}
		if tree == nil {
			continue
		}
		if selected && selectionError == nil {
			trees = append(trees, tree)
		}
		output.Traversed = append(output.Traversed, file.Path)
		stack := []ast.Node{}
		site := func(category, role string, node ast.Node) {
			a, b := fset.PositionFor(node.Pos(), false), fset.PositionFor(node.End(), false)
			start, end := -1, -1
			if a.Filename == physical && b.Filename == physical && b.Offset > a.Offset {
				start, end = a.Offset, b.Offset
			}
			output.Census = append(output.Census, Site{file.Path, category, role, start, end, len(output.Census), node, append([]ast.Node{}, stack...)})
		}
		ast.Inspect(tree, func(node ast.Node) bool {
			if node == nil {
				stack = stack[:len(stack)-1]
				return true
			}
			switch n := node.(type) {
			case *ast.File:
				site("A01", "package", n.Name)
			case *ast.FuncDecl:
				site("A02", "declaration", n)
				site("A04", "signature", n.Type)
			case *ast.TypeSpec:
				site("A02", "declaration", n)
				site("A04", "type-use", n.Type)
			case *ast.ValueSpec:
				for _, name := range n.Names {
					site("A02", "declaration", name)
				}
				if n.Type != nil {
					site("A04", "type-use", n.Type)
				}
			case *ast.Field:
				for _, name := range n.Names {
					site("A02", "declaration", name)
				}
				site("A04", "type-use", n.Type)
			case *ast.Ident:
				site("A03", "reference", n)
				site("A07", "value-access", n)
				site("A06", "callable-reference", n)
			case *ast.CallExpr:
				site("A06", "call", n)
				site("A04", "call-result-type", n)
			case *ast.ImportSpec:
				site("A03", "import", n)
				site("A09", "import-boundary", n)
			case *ast.StructType:
				for _, field := range n.Fields.List {
					if len(field.Names) == 0 {
						site("A05", "embedded-field", field.Type)
					}
				}
			case *ast.InterfaceType:
				for _, field := range n.Methods.List {
					if len(field.Names) == 0 {
						site("A05", "embedded-interface", field.Type)
					}
				}
			case *ast.IfStmt:
				site("A10", "guard", n.Cond)
			case *ast.ForStmt:
				if n.Cond != nil {
					site("A10", "guard", n.Cond)
				}
			}
			stack = append(stack, node)
			return true
		})
		// Go's AST walker intentionally skips File.Comments; detached directives must still
		// enter the denominator. Native parser comments, not a second language lexer, own spans.
		for _, group := range tree.Comments {
			for _, comment := range group.List {
				if strings.HasPrefix(comment.Text, "//go:") || strings.HasPrefix(comment.Text, "// +build") {
					site("A10", "compile-or-generate-directive", comment)
					if strings.HasPrefix(comment.Text, "//go:generate") {
						site("A08", "generation-directive", comment)
					}
				}
			}
		}
	}
	lookup := func(path string) (io.ReadCloser, error) {
		item, found := request.Exports[path]
		if !found {
			return nil, fmt.Errorf("GO_IMPORT_EXPORT_NOT_CAPTURED:%s", path)
		}
		bytes, err := os.ReadFile(item.Path)
		if err != nil || int64(len(bytes)) != item.Size || hash(bytes) != item.Sha256 {
			return nil, fmt.Errorf("GO_IMPORT_EXPORT_BYTES_UNAVAILABLE:%s", path)
		}
		return io.NopCloser(strings.NewReader(string(bytes))), nil
	}
	info := &types.Info{Types: map[ast.Expr]types.TypeAndValue{}, Defs: map[*ast.Ident]types.Object{}, Uses: map[*ast.Ident]types.Object{}, Selections: map[*ast.SelectorExpr]*types.Selection{}, Scopes: map[ast.Node]*types.Scope{}, Instances: map[*ast.Ident]types.Instance{}, Implicits: map[ast.Node]types.Object{}}
	config := types.Config{GoVersion: "go1.24", Importer: importer.ForCompiler(fset, "gc", lookup), Sizes: types.SizesFor("gc", request.Goarch), Error: func(err error) {
		if native, ok := err.(types.Error); ok {
			position := fset.PositionFor(native.Pos, false)
			output.Diagnostics = append(output.Diagnostics, Diagnostic{paths[position.Filename], "ERROR", "GO_TYPES", native.Msg})
		} else {
			output.Diagnostics = append(output.Diagnostics, Diagnostic{"", "ERROR", "GO_TYPES", "Native type checker failed"})
		}
	}}
	if config.Sizes == nil {
		panic("GO_TARGET_ARCH_UNQUALIFIED")
	}
	pkg, _ := config.Check(request.PackagePath, fset, trees, info)
	for ident, obj := range info.Defs {
		if obj != nil {
			definitions[obj] = ident
		}
	}
	for _, obj := range info.Defs {
		if named, ok := obj.(*types.TypeName); ok && !named.IsAlias() {
			owner := named.Pkg().Path() + "::" + named.Name()
			if structure, ok := named.Type().Underlying().(*types.Struct); ok {
				for i := 0; i < structure.NumFields(); i++ {
					fieldOwners[structure.Field(i).Origin()] = owner
				}
			}
			if contract, ok := named.Type().Underlying().(*types.Interface); ok {
				for i := 0; i < contract.NumExplicitMethods(); i++ {
					methodOwners[contract.ExplicitMethod(i).Origin()] = owner
				}
			}
		}
	}
	qual := func(pkg *types.Package) string { return pkg.Path() }
	typeName := func(t types.Type) string {
		if t == nil {
			return "ABSENT"
		}
		return types.TypeString(t, qual)
	}
	var sym func(types.Object) *Symbol
	sym = func(obj types.Object) *Symbol {
		if obj == nil {
			return nil
		}
		if fn, ok := obj.(*types.Func); ok {
			obj = fn.Origin()
		}
		if variable, ok := obj.(*types.Var); ok {
			obj = variable.Origin()
		}
		position := fset.PositionFor(obj.Pos(), false)
		document := paths[position.Filename]
		origin, owner := "UNKNOWN", "<universe>"
		if obj.Pkg() != nil {
			owner = obj.Pkg().Path()
			if entry, ok := request.Exports[owner]; ok {
				origin = entry.Origin
				if origin != "PLATFORM" && origin != "DEPENDENCY" {
					panic("GO_IMPORT_ORIGIN")
				}
			}
		}
		if document != "" {
			origin = "PROJECT"
			if sources[document].Role == "generated" {
				origin = "GENERATED"
			}
		}
		if obj.Pkg() == nil && obj.Parent() == types.Universe {
			origin = "PLATFORM"
		}
		kind, signature := "NATIVE_OBJECT", obj.Name()+":"+typeName(obj.Type())
		switch value := obj.(type) {
		case *types.Func:
			kind = "FUNCTION"
			sig := value.Type().(*types.Signature)
			if sig.Recv() != nil {
				owner += "::" + typeName(sig.Recv().Type())
				kind = "METHOD"
			}
			if nativeOwner, known := methodOwners[value]; known {
				owner = nativeOwner
				kind = "METHOD"
			}
			params, results := []string{}, []string{}
			for i := 0; i < sig.Params().Len(); i++ {
				params = append(params, typeName(sig.Params().At(i).Type()))
			}
			for i := 0; i < sig.Results().Len(); i++ {
				results = append(results, typeName(sig.Results().At(i).Type()))
			}
			signature = value.Name() + "(" + strings.Join(params, ",") + ")(" + strings.Join(results, ",") + ")"
			if sig.Variadic() {
				signature += " variadic"
			}
			if sig.TypeParams() != nil {
				signature += " typeparams=" + strconv.Itoa(sig.TypeParams().Len())
			}
		case *types.TypeName:
			kind = "TYPE"
		case *types.Var:
			kind = "VARIABLE"
			if value.IsField() {
				kind = "FIELD"
				if parent, ok := fieldOwners[value]; ok {
					owner = parent
				} else {
					owner += "::anonymous-field@" + document + ":" + strconv.Itoa(position.Offset)
				}
			}
		case *types.Const:
			kind = "CONSTANT"
		case *types.Builtin:
			kind = "BUILTIN"
		case *types.PkgName:
			kind = "PACKAGE"
		case *types.Label:
			kind = "LABEL"
		}
		if pkg != nil && obj.Parent() != nil && obj.Parent() != pkg.Scope() && obj.Parent() != types.Universe {
			if _, ok := obj.(*types.PkgName); !ok {
				owner += "@" + document + ":" + strconv.Itoa(position.Offset)
			}
		}
		start, end := -1, -1
		if node := definitions[obj]; node != nil {
			start, end = fset.PositionFor(node.Pos(), false).Offset, fset.PositionFor(node.End(), false).Offset
		}
		return &Symbol{owner, kind + ":" + signature, obj.Name(), kind, origin, document, start, end}
	}
	objectOf := func(node ast.Node) types.Object {
		switch n := node.(type) {
		case *ast.Ident:
			if info.Uses[n] != nil {
				return info.Uses[n]
			}
			return info.Defs[n]
		case *ast.SelectorExpr:
			if selected := info.Selections[n]; selected != nil {
				return selected.Obj()
			}
			return info.Uses[n.Sel]
		case *ast.FuncDecl:
			return info.Defs[n.Name]
		case *ast.TypeSpec:
			return info.Defs[n.Name]
		case *ast.ImportSpec:
			if n.Name != nil {
				return info.Defs[n.Name]
			}
			return info.Implicits[n]
		}
		return nil
	}
	for _, site := range output.Census {
		fact := Fact{Status: "UNRESOLVED", Reason: "GO_NATIVE_BINDING_UNAVAILABLE", Symbols: []Symbol{}}
		node := site.Node
		object := objectOf(node)
		if site.Category == "A01" {
			fact.Status = "RESOLVED"
			fact.Reason = "NATIVE_PACKAGE_MEMBERSHIP"
		}
		if ident, ok := node.(*ast.Ident); ok && object == nil {
			namespace := false
			if len(site.Parents) > 0 {
				_, namespace = site.Parents[len(site.Parents)-1].(*ast.File)
			}
			if ident.Name == "_" || namespace {
				fact.Status = "NOT_APPLICABLE"
				fact.Reason = "NATIVE_BLANK_OR_PACKAGE_DECLARATION_NAME"
			}
		}
		if site.Category == "A04" || site.Category == "A05" {
			if expr, ok := node.(ast.Expr); ok {
				t := info.TypeOf(expr)
				if t != nil && t != types.Typ[types.Invalid] {
					fact.Status = "RESOLVED"
					fact.Type = typeName(t)
					fact.Reason = "NATIVE_TYPE_INFORMATION"
				}
			}
		}
		if site.Category == "A10" {
			if comment, ok := node.(*ast.Comment); ok {
				if strings.HasPrefix(comment.Text, "//go:build") || strings.HasPrefix(comment.Text, "// +build") {
					fact.Status = "RESOLVED"
					fact.Reason = "GO_NATIVE_SELECTED_BUILD_PREDICATE"
				} else {
					fact.Status = "UNSUPPORTED"
					fact.Reason = "GO_DIRECTIVE_REQUIRES_REGISTERED_EVIDENCE"
				}
			} else {
				fact.Status = "RESOLVED"
				fact.Reason = "GO_NATIVE_RUNTIME_GUARD_TRUTH_UNKNOWN"
			}
		}
		if site.Category == "A08" {
			fact.Status = "UNSUPPORTED"
			fact.Reason = "GO_GENERATED_ARTIFACT_LINEAGE_REQUIRED"
		}
		if site.Role == "call" {
			call := node.(*ast.CallExpr)
			fun := call.Fun
			for {
				if index, ok := fun.(*ast.IndexExpr); ok {
					fun = index.X
					continue
				}
				if index, ok := fun.(*ast.IndexListExpr); ok {
					fun = index.X
					continue
				}
				break
			}
			object = objectOf(fun)
			if _, conversion := object.(*types.TypeName); conversion {
				fact.Status = "NOT_APPLICABLE"
				fact.Reason = "NATIVE_TYPE_CONVERSION_IS_NOT_FUNCTION_CALL"
				object = nil
			}
			if variable, indirect := object.(*types.Var); indirect {
				fact.Callee = sym(variable)
				object = nil
				fact.Reason = "GO_INDIRECT_CALL_TARGET_NOT_ESTABLISHED"
			}
			if selector, ok := fun.(*ast.SelectorExpr); ok {
				if selected := info.Selections[selector]; selected != nil {
					if _, isInterface := selected.Recv().Underlying().(*types.Interface); isInterface {
						fact.Dispatch = "INTERFACE"
					}
				}
			}
		}
		if site.Role == "callable-reference" {
			ident := node.(*ast.Ident)
			callee := false
			for _, parent := range site.Parents {
				if call, ok := parent.(*ast.CallExpr); ok && call.Fun.Pos() <= ident.Pos() && ident.End() <= call.Fun.End() {
					callee = true
				}
			}
			if callee {
				fact.Status = "NOT_APPLICABLE"
				fact.Reason = "REFERENCE_IS_INVOCATION_CALLEE"
				object = nil
			} else if object != nil {
				if _, ok := object.(*types.Func); !ok {
					fact.Status = "NOT_APPLICABLE"
					fact.Reason = "NATIVE_OBJECT_IS_NOT_FUNCTION"
					object = nil
				}
			}
		}
		if site.Role == "value-access" && object != nil {
			if _, ok := object.(*types.Var); !ok {
				fact.Status = "NOT_APPLICABLE"
				fact.Reason = "NATIVE_OBJECT_IS_NOT_STATE"
				object = nil
			} else {
				fact.Access = "READ"
				for _, parent := range site.Parents {
					switch n := parent.(type) {
					case *ast.AssignStmt:
						for _, lhs := range n.Lhs {
							if lhs.Pos() <= node.Pos() && node.End() <= lhs.End() {
								fact.Access = "WRITE"
								if n.Tok != token.ASSIGN && n.Tok != token.DEFINE {
									fact.Access = "READ_WRITE"
								}
							}
						}
					case *ast.IncDecStmt:
						fact.Access = "READ_WRITE"
					}
				}
			}
		}
		if symbol := sym(object); symbol != nil {
			fact.Status = "RESOLVED"
			if symbol.Origin == "UNKNOWN" {
				fact.Status = "PARTIAL"
				fact.Reason = "GO_NATIVE_ORIGIN_UNCLASSIFIED"
			}
			if fact.Dispatch == "INTERFACE" {
				fact.Status = "CONDITIONAL"
				fact.Reason = "GO_INTERFACE_RUNTIME_TARGET_NOT_ESTABLISHED"
			}
			fact.Symbols = []Symbol{*symbol}
			fact.Type = typeName(object.Type())
		}
		if fact.Status == "RESOLVED" && fact.Reason == "GO_NATIVE_BINDING_UNAVAILABLE" { fact.Reason = "" }
		if site.Category == "A06" && (site.Role == "call" || site.Role == "construct") {
			for i := len(site.Parents)-1; i >= 0; i-- {
				if _, anonymous := site.Parents[i].(*ast.FuncLit); anonymous { break }
				if function, ok := site.Parents[i].(*ast.FuncDecl); ok { fact.Caller = sym(info.Defs[function.Name]); break }
			}
			fact.CallerStatus = "UNRESOLVED"
			if fact.Caller != nil { fact.CallerStatus = "RESOLVED" } else { fact.CallerReason = "GO_NATIVE_INITIALIZER_OR_ANONYMOUS_CALLER_REQUIRED" }
		}
		output.Facts[strconv.Itoa(site.Ordinal)] = fact
	}
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetEscapeHTML(false)
	must(encoder.Encode(output))
}
