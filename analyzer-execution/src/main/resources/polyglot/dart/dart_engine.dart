// Pinned Dart 3.7.0 / analyzer 7.3.0 native API adapter. Target code is never run.
import 'dart:convert';
import 'dart:io';
import 'package:analyzer/dart/analysis/analysis_context_collection.dart';
import 'package:analyzer/dart/analysis/results.dart';
import 'package:analyzer/dart/ast/ast.dart';
import 'package:analyzer/dart/ast/visitor.dart';
import 'package:analyzer/dart/element/element.dart';
import 'package:analyzer/dart/element/type.dart';
import 'package:crypto/crypto.dart';
import 'package:path/path.dart' as p;

class Document {
  final String path,text,role,physical;final List<int> bytes;List<int> original=[];
  Document(this.path,this.text,this.role,this.physical,this.bytes);
  void mapText(String native) {
    if(native==text){original=List.generate(text.length+1,(i)=>i);return;}
    if(text.startsWith('\ufeff')&&text.substring(1)==native){original=List.generate(native.length+1,(i)=>i+1);return;}
    var buffer=StringBuffer();var offsets=<int>[0];
    for(var i=0;i<text.length;i++) {
      var value=text.codeUnitAt(i);
      if(value==13){if(i+1<text.length&&text.codeUnitAt(i+1)==10)i++;buffer.writeCharCode(10);}else{buffer.writeCharCode(value);}
      offsets.add(i+1);
    }
    var normalized=buffer.toString();var prefix=0;
    if(normalized!=native&&normalized.startsWith('\ufeff')&&normalized.substring(1)==native){prefix=1;}
    else if(normalized!=native){throw StateError('DART_ORIGINAL_TEXT_MAPPING_UNESTABLISHED');}
    original=List.generate(native.length+1,(i)=>offsets[i+prefix]);
  }
  int offset(int value)=>value<0||value>=original.length?-1:original[value];
}
class Site {
  final Document document;final String category,role;final AstNode node;final int ordinal;
  Site(this.document,this.category,this.role,this.node,this.ordinal);
  Map<String,Object?> json()=>{'document':document.path,'category':category,'role':role,'start':document.offset(node.offset),'end':document.offset(node.end),'id':ordinal,'nativeKind':node.runtimeType.toString()};
}
class LedgerVisitor extends GeneralizingAstVisitor<void> {
  final Document document;final List<Site> sites;
  LedgerVisitor(this.document,this.sites);
  void add(String category,String role,AstNode node)=>sites.add(Site(document,category,role,node,sites.length));
  @override void visitNode(AstNode node) {
    if(node is LibraryDirective||node is PartOfDirective)add('A01','library',node);
    if(node is Declaration||node is FormalParameter||node is TypeParameter)add('A02','declaration',node);
    if(node is FunctionExpression&&node.parent is! FunctionDeclaration)add('A02','anonymous-function',node);
    if(node is SimpleIdentifier)add('A03','reference',node);
    if(node is TypeAnnotation)add('A04','type-use',node);
    if(node is ExtendsClause)add('A05','extends',node.superclass);
    if(node is ImplementsClause)for(var type in node.interfaces)add('A05','implements',type);
    if(node is WithClause)for(var type in node.mixinTypes)add('A05','with-mixin',type);
    if(node is MixinOnClause)for(var type in node.superclassConstraints)add('A05','superclass-constraint',type);
    if(node is ExtensionOnClause)add('A05','on-type',node.extendedType);
    if(node is MethodInvocation||node is FunctionExpressionInvocation)add('A06','call',node);
    if(node is InstanceCreationExpression||node is SuperConstructorInvocation||node is RedirectingConstructorInvocation)add('A06','construct',node);
    if(node is SimpleIdentifier){add('A07','value-access',node);add('A06','callable-reference',node);}
    if(node is Annotation)add('A08','annotation',node);
    if(node is ImportDirective||node is ExportDirective||node is PartDirective){add('A03','import',node);add('A09','import-boundary',node);}
    if(node is IfStatement)add('A10','guard',node.expression);
    if(node is WhileStatement)add('A10','guard',node.condition);
    if(node is ConditionalExpression)add('A10','guard',node.condition);
    super.visitNode(node);
  }
}
class IndexVisitor extends GeneralizingAstVisitor<void> {
  final Map<String,List<AstNode>> nodes;
  IndexVisitor(this.nodes);
  @override void visitNode(AstNode node){nodes.putIfAbsent('${node.offset}:${node.end}:${node.runtimeType}',()=>[]).add(node);super.visitNode(node);}
}

Future<void> main(List<String> args) async {
  if(!Platform.version.startsWith('3.7.0 '))throw StateError('DART_RUNTIME_TUPLE_MISMATCH');
  var request=jsonDecode(File(args.single).readAsStringSync()) as Map<String,dynamic>;
  if(request['schema']!='native-dart-request:m8.4-v1'||request['language']!='dart'||request['dialect']!='3.7')throw StateError('DART_REQUEST_CONTEXT');
  var root=p.normalize(p.absolute(request['sourceRoot'] as String));var documents=<String,Document>{};var sites=<Site>[];var diagnostics=<Map<String,Object?>>[];var traversed=<String>[];var failed=<String>[];
  for(var file in request['files'] as List<dynamic>) {
    var path=file['path'] as String;
    if(path.isEmpty||p.posix.isAbsolute(path)||path.contains('\\')||path.contains(':')||path.split('/').any((s)=>s==''||s=='.'||s=='..'))throw StateError('DART_LOGICAL_SOURCE_PATH');
    var text=file['text'] as String;var bytes=utf8.encode(text);var physical=p.normalize(p.join(root,p.fromUri(path)));
    if(!p.isWithin(root,physical)||!p.equals(File(physical).resolveSymbolicLinksSync(),physical)||base64.encode(File(physical).readAsBytesSync())!=base64.encode(bytes)||'sha256:${sha256.convert(bytes)}'!=file['sha256'])throw StateError('DART_SOURCE_BYTES_OR_ALIAS');
    if(documents.containsKey(path))throw StateError('DART_DUPLICATE_SOURCE');documents[path]=Document(path,text,file['role'] as String? ?? 'main',physical,bytes);
  }
  if(documents.isEmpty)throw StateError('DART_EMPTY_SOURCE_MEMBERSHIP');
  Document? sourceFor(String? path){if(path==null)return null;for(var document in documents.values){if(p.equals(document.physical,path))return document;}return null;}
  String typeName(DartType? type)=>type?.getDisplayString(withNullability:true)??'ABSENT';
  var declarationNodes=<Element,List<AstNode>>{};
  AstNode? declarationNode(Element element){var nodes=declarationNodes[element.declaration??element]??[];var enclosing=nodes.where((node)=>nodes.every((other)=>node.offset<=other.offset&&other.end<=node.end)).toList();return enclosing.length==1?enclosing.single:null;}
  Map<String,Object?>? symbol(Element? raw){
    if(raw==null)return null;var element=raw.declaration??raw;var library=element.library;var source=sourceFor(element.source?.fullName);String? namespace;
    if(source!=null){var defining=sourceFor(library?.source.fullName);namespace='library:${defining?.path??source.path}';}
    else if(library!=null&&(library.isInSdk||library.source.uri.scheme=='package')){namespace=library.source.uri.toString();}
    if(namespace==null)return null;
    var owners=<String>[];for(var parent=element.enclosingElement3;parent!=null&&parent is! LibraryElement;parent=parent.enclosingElement3){if(parent is! CompilationUnitElement)owners.add(parent.displayName.isEmpty?'anonymous@${parent.nameOffset}':parent.displayName);}
    var owner=namespace+(owners.isEmpty?'':'::${owners.reversed.join('::')}');var name=element.displayName.isEmpty?'<anonymous>':element.displayName;var signature='${element.kind.name}:$name';
    if(element is ExecutableElement){signature+='(${element.parameters.map((parameter)=>'${parameter.isNamed?'named:${parameter.name}':'pos'}:${typeName(parameter.type)}').join(',')})->${typeName(element.returnType)}';signature+=' typeparams=${element.typeParameters.length}';}
    if(element is LocalVariableElement||element is ParameterElement||element.displayName.isEmpty)owner+='@${source?.path??'<native>'}:${element.nameOffset}';
    var declaration=declarationNode(element);if(element.displayName.isEmpty&&declaration!=null)owner=owner.substring(0,owner.lastIndexOf('@'))+'@${source?.path??'<native>'}:${source?.offset(declaration.offset)??declaration.offset}';
    var start=source?.offset(declaration?.offset??element.nameOffset)??-1;var end=source?.offset(declaration?.end??element.nameOffset+element.nameLength)??-1;
    return {'owner':owner,'signature':signature,'name':name,'kind':element.kind.name,'origin':source!=null?(source.role=='generated'||element.isSynthetic?'GENERATED':'PROJECT'):library?.isInSdk==true?'PLATFORM':'UNKNOWN','document':source?.path,'start':start,'end':end,'synthetic':element.isSynthetic,'locationPrecision':declaration==null?'DECLARATION_NAME':'FULL_NATIVE_DECLARATION'};
  }
  Map<String,Object?> fact(String status,{List<Map<String,Object?>> symbols=const[],String? type,String? reason,Map<String,Object?>? caller,String? truth,String? access,String? relation,Map<String,Object?>? owner})=>{'status':status,'symbols':symbols,'type':type,'reason':reason,'caller':caller,'truth':truth,'access':access,'relation':relation,'owner':owner};
  Element? binding(AstNode node){
    if(node is Declaration)return node.declaredElement;
    if(node is FormalParameter)return node.declaredElement;
    if(node is TypeParameter)return node.declaredElement;
    if(node is FunctionExpression)return node.declaredElement;
    if(node is SimpleIdentifier)return node.staticElement;
    if(node is NamedType)return node.element;
    if(node is MethodInvocation)return node.methodName.staticElement;
    if(node is FunctionExpressionInvocation)return node.staticElement;
    if(node is InstanceCreationExpression)return node.constructorName.staticElement;
    if(node is SuperConstructorInvocation)return node.staticElement;
    if(node is RedirectingConstructorInvocation)return node.staticElement;
    if(node is Annotation)return node.element;
    if(node is ImportDirective)return node.element?.importedLibrary;
    if(node is ExportDirective)return node.element?.exportedLibrary;
    return null;
  }
  var collection=AnalysisContextCollection(includedPaths:documents.values.map((d)=>d.physical).toList(),sdkPath:request['sdkPath'] as String);
  var resolved=<String,Map<String,List<AstNode>>>{};var facts=<String,Map<String,Object?>>{};
  try {
    for(var document in documents.values){
      var session=collection.contextFor(document.physical).currentSession;var parsed=session.getParsedUnit(document.physical);
      if(parsed is! ParsedUnitResult){failed.add(document.path);diagnostics.add({'document':document.path,'severity':'ERROR','code':'DART_PARSE_UNAVAILABLE','message':'Native parser returned no unit'});continue;}
      document.mapText(parsed.content);traversed.add(document.path);parsed.unit.accept(LedgerVisitor(document,sites));
      for(var error in parsed.errors){if(error.errorCode.errorSeverity.name=='ERROR'&&!failed.contains(document.path))failed.add(document.path);}
    }
    for(var document in documents.values){
      var result=await collection.contextFor(document.physical).currentSession.getResolvedUnit(document.physical);
      if(result is! ResolvedUnitResult){failed.add(document.path);diagnostics.add({'document':document.path,'severity':'ERROR','code':'DART_RESOLUTION_UNAVAILABLE','message':'Native resolver returned no unit'});continue;}
      document.mapText(result.content);var nodes=<String,List<AstNode>>{};result.unit.accept(IndexVisitor(nodes));resolved[document.path]=nodes;
      for(var values in nodes.values)for(var node in values){if(node is Declaration||node is FormalParameter||node is TypeParameter||node is FunctionExpression){var element=binding(node);if(element!=null)(declarationNodes[element.declaration??element]??=[]).add(node);}}
      for(var error in result.errors){diagnostics.add({'document':document.path,'severity':error.errorCode.errorSeverity.name,'code':error.errorCode.name,'message':error.message});}
    }
    for(var site in sites){
      var candidates=resolved[site.document.path]?['${site.node.offset}:${site.node.end}:${site.node.runtimeType}']??[];
      if(candidates.length!=1){facts['${site.ordinal}']=fact('UNRESOLVED',reason:'DART_TYPED_OCCURRENCE_UNAVAILABLE');continue;}
      var node=candidates.single;Element? element=binding(node);DartType? type=node is Expression?node.staticType:node is TypeAnnotation?node.type:null;
      Map<String,Object?>? caller;for(var parent=node.parent;parent!=null;parent=parent.parent){if(parent is MethodDeclaration||parent is FunctionDeclaration||parent is ConstructorDeclaration||parent is FunctionExpression&&parent.parent is! FunctionDeclaration){caller=symbol(binding(parent));break;}}
      if(site.category=='A10'){facts['${site.ordinal}']=fact('RESOLVED',type:typeName(type),caller:caller,truth:'UNKNOWN');continue;}
      if(site.category=='A05'){
        Map<String,Object?>? owner;for(var parent=node.parent;parent!=null;parent=parent.parent){if(parent is Declaration){owner=symbol(binding(parent));break;}}
        var target=type is InterfaceType?symbol(type.element):null;var relation={'extends':'EXTENDS','implements':'IMPLEMENTS','with-mixin':'WITH_MIXIN','superclass-constraint':'SUPERCLASS_CONSTRAINT','on-type':'ON_TYPE'}[site.role];
        var status=target==null?'UNRESOLVED':owner==null||target['origin']=='UNKNOWN'?'PARTIAL':'RESOLVED';
        facts['${site.ordinal}']=fact(status,symbols:target==null?[]:[target],type:typeName(type),owner:owner,relation:relation,reason:target==null?'DART_NATIVE_HERITAGE_TARGET_UNAVAILABLE':owner==null?'DART_NATIVE_HERITAGE_OWNER_UNAVAILABLE':target['origin']=='UNKNOWN'?'DART_NATIVE_HERITAGE_ORIGIN_UNCLASSIFIED':null);continue;
      }
      if(site.category=='A07'&&element is PropertyAccessorElement)element=element.variable2;
      if(site.role=='callable-reference'){
        if(node.parent is MethodInvocation&&(node.parent as MethodInvocation).methodName==node){facts['${site.ordinal}']=fact('NOT_APPLICABLE',reason:'REFERENCE_IS_INVOCATION_CALLEE');continue;}
        if(element!=null&&element is! ExecutableElement){facts['${site.ordinal}']=fact('NOT_APPLICABLE',reason:'NATIVE_ELEMENT_IS_NOT_EXECUTABLE');continue;}
      }
      if(site.category=='A07'&&element!=null&&element is! VariableElement){facts['${site.ordinal}']=fact('NOT_APPLICABLE',reason:'NATIVE_ELEMENT_IS_NOT_STATE');continue;}
      var target=symbol(element);
      if(target==null){
        if(site.category=='A04'&&type!=null&&type is! InvalidType){facts['${site.ordinal}']=fact('RESOLVED',type:typeName(type),caller:caller);}
        else{facts['${site.ordinal}']=fact('UNRESOLVED',reason:'DART_NATIVE_BINDING_OR_DECLARATION_MEMBERSHIP_UNAVAILABLE',caller:caller);}
        continue;
      }
      var status=target['origin']=='UNKNOWN'?'PARTIAL':'RESOLVED';String? reason;
      if(site.role=='call'&&element is MethodElement&&!element.isStatic){status='CONDITIONAL';reason='DART_VIRTUAL_RUNTIME_TARGET_NOT_ESTABLISHED';}
      if(type is InvalidType){status='PARTIAL';reason='DART_NATIVE_ERROR_TYPE';}
      String? access;
      if(site.category=='A07'&&node is SimpleIdentifier){access=node.inGetterContext()&&node.inSetterContext()?'READ_WRITE':node.inSetterContext()?'WRITE':'READ';}
      facts['${site.ordinal}']=fact(status,symbols:[target],type:typeName(type),caller:caller,reason:reason,access:access);
    }
    stdout.writeln(jsonEncode({'schema':'native-dart-projection:m8.4-v1','compiler':Platform.version.split(' ').first,'census':sites.map((s)=>s.json()).toList(),'facts':facts,'diagnostics':diagnostics,'traversed':traversed,'failed':failed}));
  } finally {await collection.dispose();}
}
