package evo.polyglot

import scala.tools.nsc.{Global,Settings}
import scala.tools.nsc.reporters.StoreReporter
import scala.reflect.internal.util.BatchSourceFile
import java.nio.file.Path
import java.util.{List => JList,Map => JMap}
import scala.jdk.CollectionConverters._
import scala.collection.mutable

/** Scala 2.13.16 native parser/typer. Written occurrences precede the typed-tree projection. */
object ScalaEngine {
  def main(args:Array[String]):Unit = {
    val out=new NeutralJvm(Path.of(args(0)))
    require(out.language=="scala" && out.dialect=="2.13.16","SCALA_PROFILE_NOT_QUALIFIED")
    val settings=new Settings;settings.Yrangepos.value=true;settings.usejavacp.value=false
    settings.classpath.value=com.evolution.analysis.graph.GraphJson.array(out.input.get("classpath")).asScala.map(_.toString).mkString(java.io.File.pathSeparator)
    settings.stopAfter.value=List("typer")
    val reporter=new StoreReporter(settings);val global=new Global(settings,reporter)
    import global._
    final case class NativeSite(tree:Tree,owner:Tree)
    val native=new mutable.HashMap[(String,Int,Int),mutable.ListBuffer[Tree]]
    val batches=out.sources.values.asScala.toList.map(s=>new BatchSourceFile(s.physical.toString,s.text.toCharArray))
    def source(tree:Tree):NeutralJvm.Source=if(!tree.pos.isDefined||tree.pos.source==null)null else out.source(Path.of(tree.pos.source.path))
    def key(tree:Tree):Option[(String,Int,Int)]=Option(source(tree)).filter(_=>tree.pos.isRange).map(s=>(s.path,tree.pos.start,tree.pos.end))
    def add(category:String,role:String,tree:Tree,owner:Tree):Unit = {
      val s=source(tree)
      if(s!=null)out.site(s,category,role,if(tree.pos.isRange)tree.pos.start else -1,if(tree.pos.isRange)tree.pos.end else -1,NativeSite(tree,owner))
    }
    val parsingRun=new global.Run
    batches.foreach { batch =>
      val before=reporter.infos.toSet
      val unit=new global.CompilationUnit(batch);val tree=global.newUnitParser(unit).parse()
      reporter.infos.filterNot(before).filter(_.severity==reporter.ERROR).foreach { info =>
        val s=out.source(Path.of(batch.path));if(s!=null)out.syntaxFailed(s,"SCALA_SYNTAX",info.msg)
      }
      object census extends Traverser {
        private var owner:Tree=EmptyTree
        override def traverse(tree:Tree):Unit={
          val previous=owner
          tree match {
            case p:PackageDef if p.pid.pos.isRange=>add("A01","namespace",p.pid,owner)
            case member:MemberDef if member.pos.isRange=>add("A02","declaration",member,owner);owner=member
            case _=>
          }
          tree match {
            case d:DefDef if d.tpt.pos.isRange=>add("A04","declared-return",d.tpt,owner)
            case v:ValDef if v.tpt.pos.isRange=>add("A04","declared-type",v.tpt,owner)
            case c:ClassDef=>c.impl.parents.filter(_.pos.isRange).foreach(t=>add("A05","parent",t,owner))
            case _:Apply if tree.pos.isRange=>add("A06","call",tree,owner)
            case _:Ident | _:Select if tree.pos.isRange=>add("A03","reference",tree,owner);add("A07","value-access",tree,owner)
            case i:Import if i.pos.isRange=>add("A09","import-boundary",i,owner)
            case i:If if i.cond.pos.isRange=>add("A10","guard",i.cond,owner)
            case c:CaseDef if c.guard.pos.isRange=>add("A10","guard",c.guard,owner)
            case _=>
          }
          tree match{case member:MemberDef=>member.mods.annotations.filter(_.pos.isRange).foreach(t=>add("A08","annotation",t,owner));case _=>}
          super.traverse(tree);owner=previous
        }
      }
      census.traverse(tree)
    }
    val run=new global.Run;run.compileSources(batches)
    run.units.foreach { unit =>
      object indexed extends Traverser {override def traverse(tree:Tree):Unit={key(tree).foreach(k=>native.getOrElseUpdate(k,mutable.ListBuffer.empty)+=tree);super.traverse(tree)}}
      indexed.traverse(unit.body)
    }
    reporter.infos.foreach { info =>
      val s=if(info.pos.isDefined)out.source(Path.of(info.pos.source.path))else null
      if(s!=null)out.diagnostic(s,if(info.severity==reporter.ERROR)"ERROR" else "WARNING","SCALA_COMPILER",info.msg)
    }
    def nativeTree(site:NativeSite):Option[Tree]=key(site.tree).flatMap(native.get).flatMap { trees =>
      val matching=trees.filter(_.productPrefix==site.tree.productPrefix)
      if(matching.size==1)matching.headOption else if(matching.isEmpty&&trees.size==1)trees.headOption else None
    }
    def nativeSymbol(tree:Tree):Symbol=tree match{case call:Apply=>call.fun.symbol;case application:TypeApply=>application.fun.symbol;case imported:Import=>imported.expr.symbol;case _=>if(tree.symbol!=null&&tree.symbol!=NoSymbol)tree.symbol else if(tree.tpe!=null)tree.tpe.typeSymbol else NoSymbol}
    def typeName(tpe:Type):String=if(tpe==null)"ABSENT" else if(tpe==ErrorType)"ERROR_TYPE" else if(tpe.typeSymbol!=NoSymbol)tpe.typeSymbol.fullName+(if(tpe.typeArgs.nonEmpty)tpe.typeArgs.map(typeName).mkString("[",",","]")else "") else tpe.toString
    def symbol(sym:Symbol):JMap[String,Object]={
      if(sym==null||sym==NoSymbol||sym.isError)return null
      val original=if(sym.isOverloaded)NoSymbol else sym
      if(original==NoSymbol)return null
      val s=if(sym.pos.isDefined&&sym.pos.source!=null)out.source(Path.of(sym.pos.source.path))else null
      val owner=if(sym.owner==NoSymbol)"<root-namespace>"else if(sym.owner.fullName.isEmpty)"<root-namespace>"else sym.owner.fullName
      val signature=(if(sym.isMethod)"METHOD:"else if(sym.isType)"TYPE:"else "VALUE:")+sym.name.decodedName.toString+(if(sym.isMethod)sym.info.paramLists.map(_.map(p=>typeName(p.info)).mkString("(",",",")")).mkString else "")
      var origin=if(s!=null)"PROJECT"else "UNKNOWN"
      if(s==null&&(definitions.isPrimitiveValueClass(sym)||definitions.isPrimitiveValueClass(sym.owner)||sym==definitions.StringClass||sym.owner==definitions.StringClass))origin="PLATFORM"
      val file=Option(sym.associatedFile).map(_.path)
      file.foreach { actual =>
        com.evolution.analysis.graph.GraphJson.array(out.input.getOrDefault("platformArtifacts",JList.of())).asScala.foreach { p =>if(actual.startsWith(p.toString+"(")||actual.startsWith(p.toString+"!")||actual.startsWith(p.toString+"/"))origin="PLATFORM" }
        if(origin=="UNKNOWN")com.evolution.analysis.graph.GraphJson.array(out.input.get("classpath")).asScala.foreach { p=>if(actual.startsWith(p.toString+"(")||actual.startsWith(p.toString+"!")||actual.startsWith(p.toString+"/"))origin="DEPENDENCY" }
      }
      val location=if(s!=null&&sym.pos.isRange)out.span(s,sym.pos.start,sym.pos.end)else null
      val value=out.symbol(owner,signature,sym.name.decodedName.toString,if(sym.isMethod)"METHOD"else if(sym.isClass)"CLASS"else if(sym.isType)"TYPE"else "VALUE",origin,location)
      value.put("nativeFqName",sym.fullName);value
    }
    out.emit { raw =>
      val site=raw.nativeNode.asInstanceOf[NativeSite]
      if(raw.category=="A01"){val value=out.fact("RESOLVED",JList.of(),null,null);value.put("namespace",site.tree.toString);value}
      else nativeTree(site) match {
        case None=>out.unresolved("SCALA_TYPED_OCCURRENCE_UNAVAILABLE")
        case Some(tree)=>
          val target=symbol(nativeSymbol(tree));val caller=nativeTree(NativeSite(site.owner,EmptyTree)).map(t=>symbol(t.symbol)).orNull
          if(raw.category=="A10") {val value=out.fact("RESOLVED",JList.of(),typeName(tree.tpe),if(caller==null)null else caller.get("id").toString);value.put("truth","UNKNOWN");value}
          else if(raw.category=="A07"&&nativeSymbol(tree)!=null&&nativeSymbol(tree)!=NoSymbol&&(nativeSymbol(tree).isMethod||nativeSymbol(tree).isType||nativeSymbol(tree).isPackage))out.fact("NOT_APPLICABLE",JList.of(),null,null)
          else if(target==null)out.unresolved("SCALA_COMPILER_BINDING_UNAVAILABLE")
          else out.fact(if(target.get("origin")=="UNKNOWN")"PARTIAL"else "RESOLVED",JList.of(target),typeName(tree.tpe),if(caller==null)null else caller.get("id").toString)
      }
    }
  }
}
