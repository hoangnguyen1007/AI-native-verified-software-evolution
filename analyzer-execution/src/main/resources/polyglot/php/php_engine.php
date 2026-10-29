<?php
declare(strict_types=1);
// PHPStan 2.1.20 static reflection/scope adapter. No target bootstrap or application entry point.
if ($argc !== 3 || PHP_MAJOR_VERSION !== 8 || PHP_MINOR_VERSION !== 1) throw new RuntimeException('PHP_NATIVE_TUPLE_MISMATCH');
$request=json_decode(file_get_contents($argv[1]),true,512,JSON_THROW_ON_ERROR);$phar=$argv[2];
if ($request['schema']!=='native-php-request:m8.4-v1'||$request['language']!=='php'||$request['dialect']!=='8.1'||'sha256:'.hash_file('sha256',$phar)!==$request['analyzer']['digest']||filesize($phar)!==$request['analyzer']['size']) throw new RuntimeException('PHP_NATIVE_REQUEST_OR_ANALYZER');
require 'phar://'.$phar.'/vendor/autoload.php';
use PhpParser\Node;
use PHPStan\Analyser\Scope;
use PHPStan\Reflection\FunctionReflection;
use PHPStan\Reflection\MethodReflection;
use PHPStan\Reflection\ClassReflection;
use PHPStan\Reflection\PropertyReflection;
use PHPStan\Type\VerbosityLevel;

$root=realpath($request['sourceRoot']);$files=[];$sources=[];$census=[];$facts=[];$diagnostics=[];$traversed=[];$failed=[];$declarations=[];$nodeSites=[];$trees=[];
foreach($request['files'] as $entry){
 $path=$entry['path'];if($path===''||$path[0]==='/'||preg_match('~[\\\\:\x00]~',$path)||array_intersect(explode('/',$path),['','.','..']))throw new RuntimeException('PHP_SOURCE_PATH');
 $physical=$root.DIRECTORY_SEPARATOR.str_replace('/',DIRECTORY_SEPARATOR,$path);$raw=$entry['text'];if(isset($sources[$path])||realpath($physical)!==$physical||file_get_contents($physical)!==$raw||'sha256:'.hash('sha256',$raw)!==$entry['sha256'])throw new RuntimeException('PHP_SOURCE_BYTES_OR_ALIAS');
 $sources[$path]=$entry;$files[$path]=$physical;
}
if(!$files)throw new RuntimeException('PHP_EMPTY_SOURCE_MEMBERSHIP');ksort($files,SORT_STRING);
$state=$request['workRoot'];if(!is_dir($state))mkdir($state,0700,true);
$config=$state.'/native.neon';file_put_contents($config,"parameters:\n    phpVersion: 80100\n    bootstrapFiles: []\n");
$factory=new PHPStan\DependencyInjection\ContainerFactory($root);
$container=$factory->create($state.'/cache',['phar://'.$phar.'/conf/config.level5.neon',$config],array_values($files),[]);
$parser=$container->getService('currentPhpVersionRichParser');$resolver=$container->getByType(PHPStan\Analyser\NodeScopeResolver::class);$scopeFactory=$container->getByType(PHPStan\Analyser\ScopeFactory::class);$provider=$container->getByType(PHPStan\Reflection\ReflectionProvider::class);
$resolver->setAnalysedFiles(array_values($files));
$fileHelper=$container->getByType(PHPStan\File\FileHelper::class);
$container->getService('pathRoutingParser')->setAnalysedFiles(array_map(fn($file)=>$fileHelper->normalizePath($file),array_values($files)));
function fact(string $status,array $symbols=[],?string $type=null,?string $reason=null,?array $caller=null,array $extra=[]):array{return ['status'=>$status,'symbols'=>$symbols,'type'=>$type,'reason'=>$reason,'caller'=>$caller]+$extra;}
function nativeKey(Node $node):string{return get_class($node).':'.$node->getStartFilePos().':'.$node->getEndFilePos();}
function signature($reflection):string{
 $variants=$reflection->getVariants();$native=[];foreach($variants as $variant){$params=[];foreach($variant->getParameters() as $p)$params[]=$p->getName().':'.$p->getType()->describe(VerbosityLevel::precise()).($p->isOptional()?'?':'').($p->isVariadic()?'...':'');$native[]='('.implode(',',$params).')->'.$variant->getReturnType()->describe(VerbosityLevel::precise());}
 sort($native,SORT_STRING);return implode('|',$native);
}
function target($reflection,?string $propertyName=null):?array{
 global $declarations,$sources,$files,$phar;
 if($reflection instanceof MethodReflection){$owner=$reflection->getDeclaringClass()->getName();$name=$reflection->getName();$qualified=$owner.'::'.$name;$file=$reflection->getDeclaringClass()->getFileName();$kind='METHOD';}
 elseif($reflection instanceof FunctionReflection){$qualified=$reflection->getName();$owner=str_contains($qualified,'\\')?substr($qualified,0,strrpos($qualified,'\\')):'<global-namespace>';$name=substr($qualified,(strrpos($qualified,'\\')===false?-1:strrpos($qualified,'\\'))+1);$file=$reflection->getFileName();$kind='FUNCTION';}
 elseif($reflection instanceof ClassReflection){$qualified=$reflection->getName();$owner=str_contains($qualified,'\\')?substr($qualified,0,strrpos($qualified,'\\')):'<global-namespace>';$name=substr($qualified,(strrpos($qualified,'\\')===false?-1:strrpos($qualified,'\\'))+1);$file=$reflection->getFileName();$kind=$reflection->isInterface()?'INTERFACE':($reflection->isTrait()?'TRAIT':(method_exists($reflection,'isEnum')&&$reflection->isEnum()?'ENUM':'CLASS'));}
 elseif($reflection instanceof PropertyReflection&&$propertyName!==null){$owner=$reflection->getDeclaringClass()->getName();$name=$propertyName;$qualified=$owner.'::$'.$name;$file=$reflection->getDeclaringClass()->getFileName();$kind='PROPERTY';}
 else{return null;}
 $key=$kind==='PROPERTY'?strtolower($owner).'::$'.$name:strtolower($qualified);$all=$declarations[$key]??[];$matches=$all;if($file!==null&&$file!==false)$matches=array_values(array_filter($matches,fn($d)=>$files[$d['document']]===realpath($file)));
 $origin='UNKNOWN';$document=null;$start=$end=-1;$lineage=null;if(count($matches)===1){$d=$matches[0];$document=$d['document'];$start=$d['start'];$end=$d['end'];$origin=($sources[$document]['role']??'main')==='generated'?'GENERATED':'PROJECT';if(($d['promotion']??false)&&$kind==='PROPERTY'){$origin='GENERATED';$lineage=['document'=>$document,'start'=>$start,'end'=>$end,'mechanism'=>'PHP_CONSTRUCTOR_PROPERTY_PROMOTION'];$start=$end=-1;}}
 elseif($reflection instanceof MethodReflection&&$reflection->getDeclaringClass()->isBuiltin())$origin='PLATFORM';
 elseif($reflection instanceof FunctionReflection&&method_exists($reflection,'isBuiltin')&&$reflection->isBuiltin())$origin='PLATFORM';
 elseif($reflection instanceof ClassReflection&&$reflection->isBuiltin())$origin='PLATFORM';
 elseif($reflection instanceof PropertyReflection&&$reflection->getDeclaringClass()->isBuiltin())$origin='PLATFORM';
 return ['owner'=>$owner,'signature'=>$kind.':'.$name.(($reflection instanceof FunctionReflection||$reflection instanceof MethodReflection)?signature($reflection):''),'name'=>$name,'kind'=>$kind,'origin'=>$origin,'document'=>$document,'start'=>$start,'end'=>$end,'ambiguous'=>count($all)>1,'lineage'=>$lineage];
}
foreach($files as $document=>$physical){
 try{$nodes=$parser->parseFile($physical);$visitor=new PhpParser\NodeTraverser();$visitor->addVisitor(new PhpParser\NodeVisitor\NameResolver(null,['preserveOriginalNames'=>true,'replaceNodes'=>false]));$nodes=$visitor->traverse($nodes);$trees[$document]=$nodes;$traversed[]=$document;}
 catch(Throwable $error){$failed[]=$document;$diagnostics[]=['document'=>$document,'severity'=>'ERROR','code'=>'PHP_NATIVE_PARSE','message'=>get_class($error)];continue;}
 $walk=function(Node $node,array $parents=[],?string $class=null)use(&$walk,$document,&$census,&$nodeSites,&$declarations){
  $site=function(string $category,string $role)use($node,$document,&$census,&$nodeSites){$id=count($census);$census[]=['document'=>$document,'category'=>$category,'role'=>$role,'start'=>$node->getStartFilePos(),'end'=>$node->getEndFilePos()+1,'id'=>$id];$nodeSites[$document][nativeKey($node)][]=$id;};
  if($node instanceof Node\Stmt\Namespace_)$site('A01','namespace');
  if($node instanceof Node\Stmt\ClassLike){$class=isset($node->namespacedName)?$node->namespacedName->toString():null;$site('A02','declaration');if($class)$declarations[strtolower($class)][]=['document'=>$document,'start'=>$node->getStartFilePos(),'end'=>$node->getEndFilePos()+1];}
  if($node instanceof Node\Stmt\Class_&&$node->extends){$node->extends->setAttribute('evoRelation','EXTENDS');$node->extends->setAttribute('evoOwner',$class);}
  if($node instanceof Node\Stmt\Class_||$node instanceof Node\Stmt\Enum_)foreach($node->implements as $name){$name->setAttribute('evoRelation','IMPLEMENTS');$name->setAttribute('evoOwner',$class);}
  if($node instanceof Node\Stmt\Interface_)foreach($node->extends as $name){$name->setAttribute('evoRelation','EXTENDS_INTERFACE');$name->setAttribute('evoOwner',$class);}
  if($node instanceof Node\Stmt\TraitUse)foreach($node->traits as $name){$name->setAttribute('evoRelation','USES_TRAIT');$name->setAttribute('evoOwner',$class);}
  $node->setAttribute('evoClass',$class);$node->setAttribute('evoParent',$parents?get_class($parents[array_key_last($parents)]):null);
  if($node instanceof Node\Stmt\Function_||$node instanceof Node\Stmt\ClassMethod){$site('A02','declaration');$site('A04','signature');$qualified=$node instanceof Node\Stmt\Function_?(isset($node->namespacedName)?$node->namespacedName->toString():$node->name->toString()):($class===null?null:$class.'::'.$node->name->toString());$node->setAttribute('evoFunctionQName',$qualified);if($qualified)$declarations[strtolower($qualified)][]=['document'=>$document,'start'=>$node->getStartFilePos(),'end'=>$node->getEndFilePos()+1];}
  if($node instanceof Node\Param||$node instanceof Node\PropertyItem||$node instanceof Node\Stmt\PropertyProperty||$node instanceof Node\Expr\Closure||$node instanceof Node\Expr\ArrowFunction)$site('A02','declaration');
  if(($node instanceof Node\PropertyItem||$node instanceof Node\Stmt\PropertyProperty)&&$class!==null)$declarations[strtolower($class).'::$'.$node->name->toString()][]=['document'=>$document,'start'=>$node->getStartFilePos(),'end'=>$node->getEndFilePos()+1];
  if($node instanceof Node\Param){foreach(array_reverse($parents) as $parent)if($parent instanceof Node\FunctionLike){$node->setAttribute('evoFunction',$parent);break;}}
  if($node instanceof Node\Param&&$node->flags!==0&&$class!==null&&$node->var instanceof Node\Expr\Variable&&is_string($node->var->name)){$declarations[strtolower($class).'::$'.$node->var->name][]=['document'=>$document,'start'=>$node->getStartFilePos(),'end'=>$node->getEndFilePos()+1,'promotion'=>true];$site('A08','promoted-property');}
  if($node instanceof Node\Name)$site('A03','reference');
  if($node instanceof Node\Name&&$node->getAttribute('evoRelation')!==null)$site('A05','heritage');
  if($node instanceof Node\Expr\FuncCall||$node instanceof Node\Expr\MethodCall||$node instanceof Node\Expr\StaticCall||$node instanceof Node\Expr\NullsafeMethodCall)$site('A06','call');
  if($node instanceof Node\Expr\New_)$site('A06','construct');
  if($node instanceof Node\Expr\Variable||$node instanceof Node\Expr\PropertyFetch||$node instanceof Node\Expr\StaticPropertyFetch)$site('A07','state-access');
  if($node instanceof Node\Attribute)$site('A08','attribute');
  if($node instanceof Node\UseItem||$node instanceof Node\Stmt\UseUse){$site('A09','import');$parent=$parents?end($parents):null;$type=$node->type!==Node\Stmt\Use_::TYPE_UNKNOWN?$node->type:($parent instanceof Node\Stmt\Use_||$parent instanceof Node\Stmt\GroupUse?$parent->type:Node\Stmt\Use_::TYPE_UNKNOWN);$name=$parent instanceof Node\Stmt\GroupUse?Node\Name::concat($parent->prefix,$node->name):$node->name;$node->setAttribute('evoUseType',$type);$node->setAttribute('evoUseName',$name);$node->name->setAttribute('evoUseType',$type);$node->name->setAttribute('evoUseName',$name);}
  if($node instanceof Node\Expr\FuncCall&&$node->name instanceof Node\Name)$node->name->setAttribute('evoFunctionReference',true);
  if($node instanceof Node\Expr\ConstFetch)$node->name->setAttribute('evoConstantReference',true);
  if($node instanceof Node\Expr\Include_||$node instanceof Node\Stmt\InlineHTML)$site('A09','resource-boundary');
  foreach($parents as $parent)if(($parent instanceof Node\Expr\Assign||$parent instanceof Node\Expr\AssignRef)&&$parent->var===$node)$node->setAttribute('evoAccess','WRITE');elseif(($parent instanceof Node\Expr\AssignOp||$parent instanceof Node\Expr\PreInc||$parent instanceof Node\Expr\PostInc||$parent instanceof Node\Expr\PreDec||$parent instanceof Node\Expr\PostDec)&&$parent->var===$node)$node->setAttribute('evoAccess','READ_WRITE');
  if($node instanceof Node\Stmt\If_||$node instanceof Node\Stmt\ElseIf_||$node instanceof Node\Stmt\While_||$node instanceof Node\Expr\Ternary)$site('A10','guard');
  foreach($node->getSubNodeNames() as $name){$child=$node->$name;if($child instanceof Node)$walk($child,[...$parents,$node],$class);elseif(is_array($child))foreach($child as $item)if($item instanceof Node)$walk($item,[...$parents,$node],$class);}
 };
 foreach($nodes as $node)$walk($node);
}
foreach($declarations as $entries)if(count($entries)>1)foreach(array_unique(array_column($entries,'document')) as $document)$diagnostics[]=['document'=>$document,'severity'=>'WARNING','code'=>'PHP_DECLARATION_CENSUS_COLLISION','message'=>'Repeated native parsed declaration identity requires condition/load-order evidence'];
function resolvedFact($reflection,?string $propertyName=null,?array $caller=null,array $extra=[]):array{
 $symbol=target($reflection,$propertyName);if($symbol===null)return fact('UNRESOLVED',reason:'PHP_NATIVE_DECLARATION_UNAVAILABLE',caller:$caller);
 $owner=$extra['owner']??null;$ambiguous=$symbol['ambiguous']||($owner['ambiguous']??false);$unknown=$symbol['origin']==='UNKNOWN'||($owner!==null&&$owner['origin']==='UNKNOWN');$status=$ambiguous?'AMBIGUOUS':($unknown?'PARTIAL':'RESOLVED');$reason=$ambiguous?'PHP_DUPLICATE_DECLARATION_BINDING_UNQUALIFIED':($unknown?'PHP_DECLARATION_ORIGIN_UNCLASSIFIED':null);
 $type=$reflection instanceof FunctionReflection||$reflection instanceof MethodReflection?signature($reflection):($reflection instanceof PropertyReflection?$reflection->getReadableType()->describe(VerbosityLevel::precise()):($reflection instanceof ClassReflection?$reflection->getName():null));
 return fact($status,[$symbol],$type,$reason,$caller,$extra);
}
function staticBinding(Node $node,array $site):?array{
 global $provider;
 if($site['category']==='A01'&&$node instanceof Node\Stmt\Namespace_)return fact('RESOLVED',type:$node->name?->toString()??'<global-namespace>');
 if($site['category']==='A02'&&$node instanceof Node\Stmt\ClassLike){$name=isset($node->namespacedName)?$node->namespacedName->toString():null;return $name!==null&&$provider->hasClass($name)?resolvedFact($provider->getClass($name)):fact('UNRESOLVED',reason:'PHP_NATIVE_CLASS_DECLARATION_UNAVAILABLE');}
 if($site['category']==='A02'&&($node instanceof Node\PropertyItem||$node instanceof Node\Stmt\PropertyProperty)){$class=$node->getAttribute('evoClass');$name=$node->name->toString();if($class!==null&&$provider->hasClass($class)){$owner=$provider->getClass($class);if($owner->hasNativeProperty($name))return resolvedFact($owner->getNativeProperty($name),$name);}return fact('UNRESOLVED',reason:'PHP_NATIVE_PROPERTY_DECLARATION_UNAVAILABLE');}
 if($node instanceof Node\Param&&$node->var instanceof Node\Expr\Variable&&is_string($node->var->name)){
  $name=$node->var->name;$class=$node->getAttribute('evoClass');
  if($site['role']==='promoted-property'&&$class!==null&&$provider->hasClass($class)){$owner=$provider->getClass($class);if($owner->hasNativeProperty($name))return resolvedFact($owner->getNativeProperty($name),$name,extra:['relation'=>'PROMOTES_PROPERTY','owner'=>target($owner)]);}
  if($site['category']==='A02'){$function=$node->getAttribute('evoFunction');$reflection=null;if($function instanceof Node\Stmt\Function_&&isset($function->namespacedName)){$qualified=new Node\Name\FullyQualified($function->namespacedName->toString());if($provider->hasFunction($qualified,null))$reflection=$provider->getFunction($qualified,null);}elseif($function instanceof Node\Stmt\ClassMethod&&$class!==null&&$provider->hasClass($class)){$owner=$provider->getClass($class);if($owner->hasNativeMethod($function->name->toString()))$reflection=$owner->getNativeMethod($function->name->toString());}
   if($reflection!==null){$caller=target($reflection);$parameters=[];foreach($reflection->getVariants() as $variant)foreach($variant->getParameters() as $parameter)if($parameter->getName()===$name)$parameters[$parameter->getType()->describe(VerbosityLevel::precise())]=$parameter;if($parameters&&$caller!==null){$types=array_keys($parameters);sort($types,SORT_STRING);$ambiguous=$caller['ambiguous']??false;$reason=$ambiguous?'PHP_NATIVE_PARAMETER_PARENT_AMBIGUOUS':(count($types)>1?'PHP_NATIVE_PARAMETER_VARIANT_AMBIGUITY':($caller['origin']==='UNKNOWN'?'PHP_NATIVE_PARAMETER_PARENT_ORIGIN_UNCAPTURED':null));$symbol=['owner'=>$caller['owner'].'::'.$caller['signature'],'signature'=>'PARAMETER:'.$name,'name'=>$name,'kind'=>'PARAMETER','origin'=>$caller['origin'],'document'=>$site['document'],'start'=>$node->getStartFilePos(),'end'=>$node->getEndFilePos()+1,'ambiguous'=>$ambiguous||count($types)>1];return fact($ambiguous?'AMBIGUOUS':($reason!==null?'PARTIAL':'RESOLVED'),[$symbol],count($types)===1?$types[0]:null,$reason)+['types'=>$types];}}
   return fact('UNRESOLVED',reason:'PHP_NATIVE_PARAMETER_CONTEXT_UNAVAILABLE');}
 }
 if($site['category']==='A08'&&$node instanceof Node\Attribute){$name=$node->name->getAttribute('resolvedName');if($name instanceof Node\Name&&$provider->hasClass($name->toString()))return resolvedFact($provider->getClass($name->toString()),extra:['relation'=>'USES_ATTRIBUTE_CLASS']);return fact('UNRESOLVED',reason:'PHP_NATIVE_ATTRIBUTE_CLASS_UNAVAILABLE');}
 if($site['role']==='import'||$node instanceof Node\Name&&$node->getAttribute('evoUseType')!==null){$name=$node->getAttribute('evoUseName');$type=$node->getAttribute('evoUseType');if(!$name instanceof Node\Name)return fact('UNRESOLVED',reason:'PHP_NATIVE_IMPORT_NAME_UNAVAILABLE');if($type===Node\Stmt\Use_::TYPE_FUNCTION){$name=new Node\Name\FullyQualified($name->toString());return $provider->hasFunction($name,null)?resolvedFact($provider->getFunction($name,null),extra:['relation'=>'IMPORTS_FUNCTION']):fact('UNRESOLVED',reason:'PHP_NATIVE_IMPORTED_FUNCTION_UNAVAILABLE');}if($type===Node\Stmt\Use_::TYPE_NORMAL)return $provider->hasClass($name->toString())?resolvedFact($provider->getClass($name->toString()),extra:['relation'=>'IMPORTS_CLASSLIKE']):fact('UNRESOLVED',reason:'PHP_NATIVE_IMPORTED_CLASS_UNAVAILABLE');return fact('UNSUPPORTED',reason:'PHP_NATIVE_CONSTANT_IMPORT_CORRESPONDENCE_REQUIRED');}
 if($node instanceof Node\Name){
  if($node->getAttribute('evoParent')===Node\Stmt\Namespace_::class)return fact('NOT_APPLICABLE',reason:'NATIVE_NAMESPACE_DECLARATION_IS_NOT_A_REFERENCE');
  if($node->getAttribute('evoFunctionReference')||$node->getAttribute('evoConstantReference'))return null;
  $name=$node->getAttribute('resolvedName');if(!$name instanceof Node\Name)return null;
  if(!$provider->hasClass($name->toString()))return fact('UNRESOLVED',reason:'PHP_NATIVE_CLASS_REFERENCE_UNAVAILABLE');$reflection=$provider->getClass($name->toString());$extra=[];
  if($site['category']==='A05'){$class=$node->getAttribute('evoOwner');if($class===null||!$provider->hasClass($class))return fact('UNRESOLVED',reason:'PHP_NATIVE_HERITAGE_OWNER_UNAVAILABLE');$extra=['relation'=>$node->getAttribute('evoRelation'),'owner'=>target($provider->getClass($class))];}
  return resolvedFact($reflection,extra:$extra);
 }
 return null;
}
// Namespace/import/type declaration reflection does not depend on callback
// visitation. Preserve an explicit fact for every independently parsed site.
foreach($trees as $document=>$nodes){$visit=function(Node $node)use(&$visit,$document,&$facts,$nodeSites){global $census;foreach($nodeSites[$document][nativeKey($node)]??[] as $id){try{$value=staticBinding($node,$census[$id]);if($value!==null)$facts[(string)$id]=$value;}catch(Throwable $failure){$facts[(string)$id]=fact('UNSUPPORTED',reason:'PHP_NATIVE_STATIC_API_'.str_replace('\\','.',get_class($failure)));}}foreach($node->getSubNodeNames() as $name){$child=$node->$name;if($child instanceof Node)$visit($child);elseif(is_array($child))foreach($child as $item)if($item instanceof Node)$visit($item);}};foreach($nodes as $node)$visit($node);}
foreach($trees as $document=>$nodes){
 $scope=$scopeFactory->create(PHPStan\Analyser\ScopeContext::create($files[$document]));
 try{$resolver->processNodes($nodes,$scope,function(Node $node,Scope $scope)use($document,&$facts,$provider,$nodeSites){
  foreach($nodeSites[$document][nativeKey($node)]??[] as $id){if(isset($facts[(string)$id]))continue;global $census;$site=$census[$id];$value=fact('UNSUPPORTED',reason:'PHP_NATIVE_ARCHITECTURAL_SUPPLEMENT_REQUIRED');
   try{$caller=$scope->getFunction();$caller=$caller===null?null:target($caller);$reflection=null;
    if($site['category']==='A10')$value=fact('RESOLVED',caller:$caller,extra:['truth'=>'UNKNOWN']);
    elseif($node instanceof Node\Expr\FuncCall&&$node->name instanceof Node\Name){if($provider->hasFunction($node->name,$scope))$reflection=$provider->getFunction($node->name,$scope);else $value=fact('UNRESOLVED',reason:'PHP_NATIVE_FUNCTION_BINDING_UNAVAILABLE',caller:$caller);}
    elseif(($node instanceof Node\Expr\MethodCall||$node instanceof Node\Expr\NullsafeMethodCall)&&$node->name instanceof Node\Identifier){$type=$scope->getType($node->var);if($type->hasMethod($node->name->toString())->yes())$reflection=$scope->getMethodReflection($type,$node->name->toString());else $value=fact('UNRESOLVED',reason:'PHP_DYNAMIC_OR_NATIVE_METHOD_BINDING_UNAVAILABLE',caller:$caller);}
    elseif($node instanceof Node\Expr\StaticCall&&$node->class instanceof Node\Name&&$node->name instanceof Node\Identifier){$name=$scope->resolveName($node->class);if($provider->hasClass($name)){ $class=$provider->getClass($name);if($class->hasMethod($node->name->toString()))$reflection=$class->getMethod($node->name->toString(),$scope);}}
    elseif($node instanceof Node\Stmt\Function_&&isset($node->namespacedName)){$name=new Node\Name\FullyQualified($node->namespacedName->toString());if($provider->hasFunction($name,$scope))$reflection=$provider->getFunction($name,$scope);}
    elseif($node instanceof Node\Stmt\ClassMethod&&$scope->isInClass()){$class=$scope->getClassReflection();if($class->hasMethod($node->name->toString()))$reflection=$class->getMethod($node->name->toString(),$scope);}
    elseif($site['category']==='A07'&&($node instanceof Node\Expr\PropertyFetch||$node instanceof Node\Expr\NullsafePropertyFetch)&&$node->name instanceof Node\Identifier){$type=$scope->getType($node->var);$name=$node->name->toString();if($type->hasProperty($name)->yes()){$property=$scope->getPropertyReflection($type,$name);if($property!==null&&$property->getDeclaringClass()->hasNativeProperty($name))$value=resolvedFact($property,$name,$caller,['access'=>$node->getAttribute('evoAccess')??'READ']);else $value=fact('PARTIAL',type:$scope->getType($node)->describe(VerbosityLevel::precise()),reason:'PHP_DYNAMIC_PROPERTY_DECLARATION_UNAVAILABLE',caller:$caller);}else $value=fact('UNRESOLVED',reason:'PHP_NATIVE_PROPERTY_BINDING_UNAVAILABLE',caller:$caller);}
    elseif($site['category']==='A07'&&$node instanceof Node\Expr)$value=fact('PARTIAL',type:$scope->getType($node)->describe(VerbosityLevel::precise()),reason:'PHP_NATIVE_STATE_DECLARATION_AND_ACCESS_MODE_SUPPLEMENT_REQUIRED',caller:$caller);
    elseif($site['category']==='A06')$value=fact('UNRESOLVED',reason:'PHP_INDIRECT_OR_CONSTRUCTOR_RUNTIME_TARGET_NOT_ESTABLISHED',caller:$caller);
    if($reflection instanceof MethodReflection&&!$reflection->getDeclaringClass()->hasNativeMethod($reflection->getName())){$value=fact('UNRESOLVED',reason:'PHP_NATIVE_DYNAMIC_OR_SYNTHETIC_METHOD_IS_NOT_DECLARATION_PROOF',caller:$caller);$reflection=null;}
    if($reflection!==null){$value=resolvedFact($reflection,caller:$caller);if($site['role']==='call'&&$reflection instanceof MethodReflection&&!$reflection->isStatic()&&$value['status']==='RESOLVED'){$value['status']='CONDITIONAL';$value['reason']='PHP_VIRTUAL_RUNTIME_TARGET_NOT_ESTABLISHED';}if($node instanceof Node\Expr\FuncCall&&$node->name instanceof Node\Name)foreach($nodeSites[$document][nativeKey($node->name)]??[] as $reference){if($census[$reference]['category']==='A03')$facts[(string)$reference]=resolvedFact($reflection,caller:$caller);}}
   }catch(Throwable $failure){$value=fact('UNSUPPORTED',reason:'PHP_NATIVE_API_OUTCOME_'.str_replace('\\','.',get_class($failure)));}
   $facts[(string)$id]=$value;
  }
 });}catch(Throwable $failure){$failed[]=$document;$diagnostics[]=['document'=>$document,'severity'=>'ERROR','code'=>'PHP_NATIVE_SCOPE_TRAVERSAL','message'=>get_class($failure)];}
}
$analysis=$container->getByType(PHPStan\Analyser\Analyser::class)->analyse(array_values($files),null,null,false,array_values($files));
foreach($analysis->getErrors() as $error){
 $physical=realpath($error->getFilePath());$document=array_search($physical,$files,true);
 // getFile() is a presentation label and may include a trait context. The native
 // getFilePath() identifies the physical source. Never discard an unmapped error.
 if($document!==false)$diagnostics[]=['document'=>$document,'severity'=>'ERROR','code'=>$error->getIdentifier()??'PHPSTAN_NATIVE','message'=>$error->getMessage()];
 else foreach(array_keys($files) as $path){$failed[]=$path;$diagnostics[]=['document'=>$path,'severity'=>'ERROR','code'=>'PHP_NATIVE_DIAGNOSTIC_SOURCE_UNMAPPED','message'=>$error->getIdentifier()??'PHPSTAN_NATIVE'];}
}
// Native scope traversal intentionally omits some syntax-only name nodes. Retain them
// as explicit unsupported supplements; they cannot shrink the independent denominator.
foreach($census as $site)if(!isset($facts[(string)$site['id']]))$facts[(string)$site['id']]=fact('UNSUPPORTED',reason:'PHP_NATIVE_CALLBACK_DID_NOT_ATTRIBUTE_SYNTAX');
echo json_encode(['schema'=>'native-php-projection:m8.4-v1','compiler'=>PHP_VERSION,'census'=>$census,'facts'=>(object)$facts,'diagnostics'=>$diagnostics,'traversed'=>$traversed,'failed'=>$failed],JSON_THROW_ON_ERROR|JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES),"\n";
