// Trusted compiler adapter. Captured application code is parsed/bound, never emitted or executed.
using System;
using System.IO;
using System.Linq;
using System.Collections;
using System.Collections.Generic;
using System.Text;
using System.Text.Json;
using System.Security.Cryptography;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.Text;
using Microsoft.CodeAnalysis.Operations;
using CS=Microsoft.CodeAnalysis.CSharp;
using VB=Microsoft.CodeAnalysis.VisualBasic;
using CSS=Microsoft.CodeAnalysis.CSharp.Syntax;
using VBS=Microsoft.CodeAnalysis.VisualBasic.Syntax;

namespace Evo.Polyglot;

public static class RoslynEngine {
    sealed record Document(string Path,string Text,string Role,SyntaxTree Tree,byte[] Bytes);
    sealed record Site(Document Document,string Category,string Role,SyntaxNode? Node);
    static readonly UTF8Encoding Utf8=new(false,true);
    static readonly StringComparer Ordinal=StringComparer.Ordinal;
    static JsonElement Input;static string Language="",Scope="",Repository="",Package="";
    static Compilation Compilation=null!;
    static readonly List<Document> Documents=new();static readonly List<Site> Sites=new();
    static readonly Dictionary<SyntaxNode,int> NativeLocators=new();
    static readonly Dictionary<SyntaxTree,Document> ByTree=new();
    static readonly Dictionary<IAssemblySymbol,string> AssemblyOrigins=new(SymbolEqualityComparer.Default);
    static readonly string[] Categories=Enumerable.Range(1,12).Select(n=>"A"+n.ToString("00")).ToArray();
    static Dictionary<string,object?> Obj(params (string Key,object? Value)[] values)=>values.ToDictionary(x=>x.Key,x=>x.Value,Ordinal);
    static string Text(JsonElement o,string key)=>o.GetProperty(key).GetString()??throw new ArgumentException("NATIVE_STRING_REQUIRED");
    static void Unique(JsonElement element){if(element.ValueKind==JsonValueKind.Object){var names=new HashSet<string>(Ordinal);foreach(var p in element.EnumerateObject()){if(!names.Add(p.Name))throw new ArgumentException("ROSLYN_DUPLICATE_JSON_FIELD");Unique(p.Value);}}else if(element.ValueKind==JsonValueKind.Array)foreach(var item in element.EnumerateArray())Unique(item);}
    static void NoLinks(string path){for(FileSystemInfo? file=File.Exists(path)?new FileInfo(path):new DirectoryInfo(path);file!=null;file=file is FileInfo f?f.Directory:((DirectoryInfo)file).Parent)if((file.Attributes&FileAttributes.ReparsePoint)!=0)throw new ArgumentException("ROSLYN_INPUT_ALIAS");}
    static void Logical(string p){if(String.IsNullOrWhiteSpace(p)||p.StartsWith('/')||p.Contains('\\')||p.Contains(':')||p.Contains('\0')||p.Split('/').Any(x=>x is "" or "." or ".."))throw new ArgumentException("ROSLYN_SOURCE_PATH");}
    static string Quote(string s){var b=new StringBuilder("\"");for(int i=0;i<s.Length;i++){char c=s[i];b.Append(c switch {'"'=>"\\\"",'\\'=>"\\\\",'\b'=>"\\b",'\f'=>"\\f",'\n'=>"\\n",'\r'=>"\\r",'\t'=>"\\t",_=>c<32?"\\u"+((int)c).ToString("x4"):c.ToString()});if(char.IsHighSurrogate(c)){if(i+1==s.Length||!char.IsLowSurrogate(s[i+1]))throw new ArgumentException("NATIVE_SURROGATE");b.Append(s[++i]);}else if(char.IsLowSurrogate(c))throw new ArgumentException("NATIVE_SURROGATE");}return b.Append('"').ToString();}
    static string Canonical(object? o)=>o switch {
        null=>"null",string s=>Quote(s),bool b=>b?"true":"false",int n=>n.ToString(System.Globalization.CultureInfo.InvariantCulture),long n=>n.ToString(System.Globalization.CultureInfo.InvariantCulture),
        JsonElement e=>e.ValueKind switch {JsonValueKind.Object=>Canonical(e.EnumerateObject().ToDictionary(p=>p.Name,p=>(object?)p.Value,Ordinal)),JsonValueKind.Array=>Canonical(e.EnumerateArray().Select(p=>(object?)p).ToArray()),JsonValueKind.String=>Quote(e.GetString()!),JsonValueKind.True=>"true",JsonValueKind.False=>"false",JsonValueKind.Null=>"null",JsonValueKind.Number=>e.GetRawText(),_=>throw new ArgumentException("NATIVE_JSON_KIND")},
        IDictionary<string,object?> d=>"{"+String.Join(",",d.OrderBy(x=>x.Key,Ordinal).Select(x=>Quote(x.Key)+":"+Canonical(x.Value)))+"}",
        IEnumerable a=>"["+String.Join(",",a.Cast<object?>().Select(Canonical))+"]",_=>throw new ArgumentException("NATIVE_CANONICAL_TYPE")};
    static string Hash(byte[] raw)=>"sha256:"+Convert.ToHexString(SHA256.HashData(raw)).ToLowerInvariant();
    static string Digest(object? value)=>Hash(Utf8.GetBytes(Canonical(value)));
    static void Write(object value)=>Console.WriteLine(Canonical(value));
    static object? Span(Document doc,TextSpan span){if(span.IsEmpty)return null;int a=span.Start,b=span.End;if(a<0||b>doc.Text.Length||(a>0&&a<doc.Text.Length&&char.IsLowSurrogate(doc.Text[a]))||(b<doc.Text.Length&&char.IsLowSurrogate(doc.Text[b])))throw new ArgumentException("ROSLYN_SPAN_INVALID");var lines=doc.Tree.GetText().Lines.GetLinePositionSpan(span);return Obj(("path",doc.Path),("start",a),("end",b),("startLine",lines.Start.Line+1),("startColumn",lines.Start.Character+1),("endLine",lines.End.Line+1),("endColumn",lines.End.Character+1));}
    static string Key(Site s)=>s.Document.Path+":"+s.Category+":"+(s.Node==null?"-00000000001:-00000000001":s.Node.SpanStart.ToString("000000000000")+":"+s.Node.Span.End.ToString("000000000000"))+":"+s.Role+(s.Node==null?"":":"+s.Node.RawKind+":"+NativeLocators[s.Node].ToString("000000000000"));
    static void Add(Document d,string c,string r,SyntaxNode n){if(!n.Span.IsEmpty){if(!NativeLocators.ContainsKey(n))NativeLocators.Add(n,NativeLocators.Count);Sites.Add(new(d,c,r,n));}}
    static void Census(Document d){
        foreach(var n in d.Tree.GetRoot().DescendantNodesAndSelf(descendIntoTrivia:true)){
            if(n is CSS.BaseNamespaceDeclarationSyntax or VBS.NamespaceStatementSyntax)Add(d,"A01","namespace",n);
            if(n is CSS.BaseTypeDeclarationSyntax or CSS.BaseMethodDeclarationSyntax or CSS.DelegateDeclarationSyntax or CSS.PropertyDeclarationSyntax or CSS.EventDeclarationSyntax or CSS.VariableDeclaratorSyntax or CSS.ParameterSyntax or CSS.TypeParameterSyntax or CSS.LocalFunctionStatementSyntax
                or VBS.TypeStatementSyntax or VBS.MethodStatementSyntax or VBS.SubNewStatementSyntax or VBS.PropertyStatementSyntax or VBS.EventStatementSyntax or VBS.ModifiedIdentifierSyntax or VBS.ParameterSyntax or VBS.TypeParameterSyntax)Add(d,"A02","declaration",n);
            if(n is CSS.SimpleNameSyntax or VBS.SimpleNameSyntax) {Add(d,"A03","reference",n);Add(d,"A07","value-access",n);Add(d,"A06","callable-reference",n);}
            if(n is CSS.TypeSyntax or VBS.TypeSyntax)Add(d,"A04","type-use",n);
            if(n is CSS.BaseTypeSyntax or VBS.InheritsStatementSyntax or VBS.ImplementsStatementSyntax)Add(d,"A05","heritage",n);
            if(n is CSS.InvocationExpressionSyntax or VBS.InvocationExpressionSyntax)Add(d,"A06","call",n);
            if(n is CSS.BaseObjectCreationExpressionSyntax or VBS.ObjectCreationExpressionSyntax)Add(d,"A06","construct",n);
            if(n is CSS.AttributeSyntax or VBS.AttributeSyntax)Add(d,"A08","attribute",n);
            if(n is CSS.UsingDirectiveSyntax or CSS.ExternAliasDirectiveSyntax or VBS.ImportsClauseSyntax){Add(d,"A03","import",n);Add(d,"A09","import-boundary",n);}
            if(n is CSS.DirectiveTriviaSyntax or VBS.DirectiveTriviaSyntax)Add(d,"A10","compile-directive",n);
            if(n is CSS.IfStatementSyntax a)Add(d,"A10","guard",a.Condition);
            if(n is VBS.IfStatementSyntax b)Add(d,"A10","guard",b.Condition);
        }
        foreach(string c in Categories){if(c is "A01" or "A11" or "A12")Sites.Add(new(d,c,"scope",null));else if(!Sites.Any(s=>s.Document==d&&s.Category==c))Sites.Add(new(d,c,"syntax-absent",null));}
    }
    static string Type(ITypeSymbol? type)=>type==null?"ABSENT":type.TypeKind==TypeKind.Error?"ERROR_TYPE":type.ToDisplayString(SymbolDisplayFormat.FullyQualifiedFormat);
    static Dictionary<string,object?>? Symbol(ISymbol? symbol){
        if(symbol==null||symbol is ITypeSymbol {TypeKind:TypeKind.Error})return null;symbol=symbol.OriginalDefinition;
        var source=symbol.Locations.FirstOrDefault(l=>l.IsInSource&&l.SourceTree!=null&&ByTree.ContainsKey(l.SourceTree));
        string owner=(symbol.ContainingSymbol?.ToDisplayString(SymbolDisplayFormat.FullyQualifiedFormat))??"<root-namespace>";if(owner=="")owner="<root-namespace>";
        string signature=DocumentationCommentId.CreateDeclarationId(symbol)??symbol.Kind+":"+symbol.ToDisplayString(SymbolDisplayFormat.CSharpErrorMessageFormat);
        if(symbol.Kind is SymbolKind.Local or SymbolKind.RangeVariable or SymbolKind.Label || symbol is IMethodSymbol {MethodKind:MethodKind.AnonymousFunction})if(source!=null)owner+="@"+ByTree[source.SourceTree!].Path+":"+source.SourceSpan.Start;
        string origin=source!=null?"PROJECT":symbol.ContainingAssembly!=null&&AssemblyOrigins.TryGetValue(symbol.ContainingAssembly,out var binary)?binary:symbol is INamespaceSymbol ns&&ns.IsGlobalNamespace?"PLATFORM":"UNKNOWN";
        var id=Digest(new[]{"language-symbol:m8.4-v1",Language,Repository,Package,owner,signature});
        return Obj(("id",id),("name",symbol.Name),("kind",symbol.Kind.ToString()),("owner",owner),("signature",signature),("origin",origin),("span",source==null?null:Span(ByTree[source.SourceTree!],source.SourceSpan)),("locationPrecision","DECLARATION_NAME"),("accessibility",symbol.DeclaredAccessibility.ToString()),("implicit",symbol.IsImplicitlyDeclared));
    }
    static Dictionary<string,object?> Fact(string status,IEnumerable<Dictionary<string,object?>>? symbols=null,string? type=null,string? caller=null,string? reason=null){var values=symbols?.ToArray()??Array.Empty<Dictionary<string,object?>>();return Obj(("status",status),("symbols",values),("targets",values.Select(s=>s["id"]).Distinct().ToArray()),("type",type),("caller",caller),("reason",reason),("uncertainty",status is "PARTIAL" or "AMBIGUOUS" or "CANDIDATES" or "CONDITIONAL"?new[]{reason??"NATIVE_BINDING_RETAINS_UNCERTAINTY"}:Array.Empty<string>()));}
    static Dictionary<string,object?> Attributed(Site site,Diagnostic[] diagnostics){
        var d=site.Document;var n=site.Node;
        if(site.Category=="A11")return Obj(("status",diagnostics.Any(x=>x.Severity==DiagnosticSeverity.Error)?"ERROR":"RESOLVED"),("targets",Array.Empty<string>()),("diagnostics",diagnostics.Select(x=>Obj(("code",x.Id),("severity",x.Severity==DiagnosticSeverity.Error?"ERROR":x.Severity.ToString().ToUpperInvariant()),("message",x.GetMessage(System.Globalization.CultureInfo.InvariantCulture)),("span",x.Location.IsInSource?Span(d,x.Location.SourceSpan):null))).ToArray()));
        if(site.Role=="scope")return Fact("RESOLVED");
        if(d.Tree.GetDiagnostics().Any(x=>x.Severity==DiagnosticSeverity.Error))return Fact("ERROR",reason:"ROSLYN_SYNTAX_RECOVERY");
        if(site.Role=="syntax-absent"&&site.Category=="A08")return Fact("UNSUPPORTED",reason:"NATIVE_IMPLICIT_GENERATION_CENSUS_REQUIRED");
        if(site.Role=="syntax-absent")return Fact("NOT_APPLICABLE",reason:"NATIVE_SYNTAX_CATEGORY_ABSENT");
        var model=Compilation.GetSemanticModel(d.Tree);var caller=Symbol(model.GetEnclosingSymbol(n!.SpanStart));
        if(site.Category=="A10"){var value=Fact("RESOLVED",caller:caller?["id"]?.ToString());value["truth"]="UNKNOWN";value["nativeKind"]=n.GetType().Name;return value;}
        var declared=model.GetDeclaredSymbol(n);SymbolInfo info=default;TypeInfo type=default;
        var bindingNode=n is CSS.BaseTypeSyntax b?b.Type:n is CSS.UsingDirectiveSyntax u&&u.Name!=null?u.Name:n is VBS.SimpleImportsClauseSyntax i?i.Name:n;
        if(declared==null){info=model.GetSymbolInfo(bindingNode);type=model.GetTypeInfo(bindingNode);}
        ISymbol? binding=declared??info.Symbol;
        if(site.Role=="callable-reference"){
            if(binding is not IMethodSymbol)return binding==null?Fact("UNRESOLVED",reason:"ROSLYN_REFERENCE_BINDING_UNAVAILABLE"):Fact("NOT_APPLICABLE",reason:"NATIVE_SYMBOL_IS_NOT_METHOD");
            if(n.Ancestors().Any(a=>a is CSS.InvocationExpressionSyntax cs&&cs.Expression.Span.Contains(n.Span)||a is VBS.InvocationExpressionSyntax vb&&vb.Expression.Span.Contains(n.Span)))return Fact("NOT_APPLICABLE",reason:"REFERENCE_IS_INVOCATION_CALLEE");
        }
        if(site.Category=="A07"&&binding!=null&&binding.Kind is not (SymbolKind.Field or SymbolKind.Property or SymbolKind.Event or SymbolKind.Local or SymbolKind.Parameter or SymbolKind.RangeVariable))return Fact("NOT_APPLICABLE",reason:"NATIVE_SYMBOL_IS_NOT_STATE");
        if(site.Category=="A04"&&binding!=null&&binding is not ITypeSymbol&&binding is not IAliasSymbol)return Fact("NOT_APPLICABLE",reason:"NATIVE_NAME_IS_NOT_TYPE");
        var target=Symbol(binding);
        if(target==null){var candidates=info.CandidateSymbols.Select(Symbol).Where(s=>s!=null).Cast<Dictionary<string,object?>>().ToArray();if(candidates.Length>0)return Fact(info.CandidateReason==CandidateReason.Ambiguous?"AMBIGUOUS":"CANDIDATES",candidates,Type(type.Type),caller?["id"]?.ToString(),info.CandidateReason.ToString());return Fact("UNRESOLVED",reason:model.GetOperation(n) is IDynamicInvocationOperation or IDynamicMemberReferenceOperation?"ROSLYN_DYNAMIC_DISPATCH":"ROSLYN_COMPILER_BINDING_UNAVAILABLE");}
        bool invalid=type.Type?.TypeKind==TypeKind.Error||diagnostics.Any(x=>x.Severity==DiagnosticSeverity.Error&&x.Location.IsInSource&&x.Location.SourceSpan.IntersectsWith(n.Span));
        var result=Fact(invalid||Equals(target["origin"],"UNKNOWN")?"PARTIAL":"RESOLVED",new[]{target},Type(type.Type??(binding switch {IFieldSymbol f=>f.Type,IPropertySymbol p=>p.Type,ILocalSymbol l=>l.Type,IParameterSymbol p=>p.Type,IMethodSymbol m=>m.ReturnType,_=>binding as ITypeSymbol})),caller?["id"]?.ToString(),invalid?"ROSLYN_ERROR_INTERSECTS_OCCURRENCE":null);
        if(site.Category=="A07"){
            var operation=model.GetOperation(n);var parent=operation?.Parent;
            result["access"]=parent is ICompoundAssignmentOperation compound&&compound.Target==operation||parent is IIncrementOrDecrementOperation increment&&increment.Target==operation?"READ_WRITE":parent is ISimpleAssignmentOperation assignment&&assignment.Target==operation?"WRITE":parent is IArgumentOperation arg&&arg.Parameter?.RefKind==RefKind.Out?"WRITE":parent is IArgumentOperation byref&&byref.Parameter?.RefKind==RefKind.Ref?"READ_WRITE":"READ";
        }
        return result;
    }
    public static void Main(string[] args){
        Input=JsonDocument.Parse(File.ReadAllBytes(args.Single())).RootElement.Clone();Unique(Input);if(Text(Input,"schema")!="native-roslyn-request:m8.4-v1")throw new ArgumentException("ROSLYN_REQUEST_SCHEMA");
        if(Text(Input,"compilerVersion")!=typeof(Compilation).Assembly.GetName().Version!.ToString())throw new ArgumentException("ROSLYN_COMPILER_VERSION_MISMATCH");
        Language=Text(Input,"language");if(Language is not ("csharp" or "visualbasic"))throw new ArgumentException("ROSLYN_LANGUAGE");Scope=Text(Input,"scope");Repository=Text(Input,"repositoryScope");Package=Text(Input,"packageScope");
        string version=Text(Input,"dialect");if(version!=(Language=="csharp"?"12.0":"16.9"))throw new ArgumentException("ROSLYN_DIALECT_UNQUALIFIED");
        var symbols=Input.GetProperty("symbols").EnumerateArray().Select(x=>x.GetString()!).ToArray();var root=Path.GetFullPath(Text(Input,"sourceRoot"));NoLinks(root);
        foreach(var entry in Input.GetProperty("files").EnumerateArray()){
            string path=Text(entry,"path");Logical(path);string text=Text(entry,"text");byte[] raw=Utf8.GetBytes(text);if(Hash(raw)!=Text(entry,"sha256"))throw new ArgumentException("ROSLYN_SOURCE_HASH");
            var physical=Path.GetFullPath(Path.Combine(root,path));NoLinks(physical);if(!physical.StartsWith(root+Path.DirectorySeparatorChar,OperatingSystem.IsWindows()?StringComparison.OrdinalIgnoreCase:StringComparison.Ordinal)||!File.ReadAllBytes(physical).SequenceEqual(raw))throw new ArgumentException("ROSLYN_SOURCE_BYTES");
            SyntaxTree tree=Language=="csharp"?CS.CSharpSyntaxTree.ParseText(SourceText.From(text,Utf8),new CS.CSharpParseOptions(CS.LanguageVersion.CSharp12,preprocessorSymbols:symbols),physical):VB.VisualBasicSyntaxTree.ParseText(SourceText.From(text,Utf8),new VB.VisualBasicParseOptions(VB.LanguageVersion.VisualBasic16_9,preprocessorSymbols:symbols.Select(s=>new KeyValuePair<string,object>(s,true))),physical);
            var doc=new Document(path,text,entry.TryGetProperty("role",out var role)?role.GetString()!:"main",tree,raw);if(Documents.Any(d=>d.Path==path))throw new ArgumentException("ROSLYN_DUPLICATE_SOURCE");Documents.Add(doc);ByTree.Add(tree,doc);Census(doc);
        }
        if(Documents.Count==0)throw new ArgumentException("ROSLYN_EMPTY_SOURCE_MEMBERSHIP");
        foreach(var reference in Input.GetProperty("references").EnumerateArray()){
            string path=Text(reference,"path");NoLinks(path);var bytes=File.ReadAllBytes(path);
            if(bytes.LongLength!=reference.GetProperty("size").GetInt64()||Hash(bytes)!=Text(reference,"sha256")||Text(reference,"origin") is not ("PLATFORM" or "DEPENDENCY"))throw new ArgumentException("ROSLYN_REFERENCE_BYTES_OR_ORIGIN");
        }
        var refs=Input.GetProperty("references").EnumerateArray().Select(x=>MetadataReference.CreateFromFile(Text(x,"path"))).ToArray();
        Compilation=Language=="csharp"?CS.CSharpCompilation.Create(Package,Documents.Select(d=>d.Tree),refs,new CS.CSharpCompilationOptions(OutputKind.DynamicallyLinkedLibrary)):VB.VisualBasicCompilation.Create(Package,Documents.Select(d=>d.Tree),refs,new VB.VisualBasicCompilationOptions(OutputKind.DynamicallyLinkedLibrary,optionStrict:VB.OptionStrict.On));
        for(int i=0;i<refs.Length;i++){var captured=Input.GetProperty("references")[i];var assembly=Compilation.GetAssemblyOrModuleSymbol(refs[i]) as IAssemblySymbol;if(assembly!=null)AssemblyOrigins[assembly]=Text(captured,"origin");}
        var diagnostics=Compilation.GetDiagnostics();var census=new List<object>();var observations=new List<object>();var unique=new HashSet<string>(Ordinal);
        foreach(var site in Sites.OrderBy(Key,Ordinal)){
            var key=Key(site);if(!unique.Add(key))throw new ArgumentException("ROSLYN_DUPLICATE_CENSUS");var location=site.Node==null?null:Span(site.Document,site.Node.Span);
            var obligation=Obj(("kind","CENSUS"),("key",key),("scope",Scope),("fields",Obj(("document",site.Document.Path),("category",site.Category),("role",site.Role),("span",location))));census.Add(obligation);
            var observed=Attributed(site,diagnostics.Where(x=>x.Location.SourceTree==site.Document.Tree||!x.Location.IsInSource).ToArray());observed["role"]=site.Role;observed["span"]=location;observed["obligation"]=Digest(obligation);observations.Add(Obj(("kind","OBSERVATION"),("key",key),("scope",Scope),("fields",observed)));
        }
        Write(Obj(("schema","language-capture-result:m8.4-v1"),("scope",Scope),("language",Language),("dialect",version),("provider",Input.GetProperty("provider")),("tool",Input.GetProperty("tool")),("trust","PRODUCER_ASSERTION")));
        foreach(var doc in Documents.OrderBy(d=>d.Path,Ordinal))Write(Obj(("kind","DOCUMENT"),("key",doc.Path),("scope",Scope),("fields",Obj(("content",Obj(("digest",Hash(doc.Bytes)),("size",doc.Bytes.Length))),("language",Language),("role",doc.Role)))));
        foreach(var row in census)Write(row);foreach(var row in observations)Write(row);
        Write(Obj(("schema","language-capture-end:m8.4-v1"),("scope",Scope),("documents",Documents.Count),("census",census.Count),("observations",observations.Count),("diagnostics",diagnostics.Select(x=>Obj(("code",x.Id),("severity",x.Severity==DiagnosticSeverity.Error?"ERROR":x.Severity.ToString().ToUpperInvariant()),("message",x.GetMessage(System.Globalization.CultureInfo.InvariantCulture)))).ToArray())));
    }
}
