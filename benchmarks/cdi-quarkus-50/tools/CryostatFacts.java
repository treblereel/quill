import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.lang.model.element.*;
import javax.tools.*;
import java.nio.file.*;
import java.util.*;

/** Independent javac inventory: parsing/attribution only; never executes processors or application code. */
public class CryostatFacts {
  static Trees trees;
  static String identity(Element e) {
    if(e==null)return null;
    if(e instanceof TypeElement t)return t.getQualifiedName().toString();
    String owner=identity(e.getEnclosingElement());
    if(e instanceof ExecutableElement m)return owner+"#"+m.getSimpleName()+"("+String.join(",",m.getParameters().stream().map(p->p.asType().toString()).toList())+")";
    return owner+"#"+e.getSimpleName();
  }
  static List<Map<String,Object>> annotations(Element e) {
    var out=new ArrayList<Map<String,Object>>();
    if(e!=null)for(var a:e.getAnnotationMirrors()) {
      var values=new TreeMap<String,String>();a.getElementValues().forEach((k,v)->values.put(k.getSimpleName().toString(),v.toString()));
      out.add(Map.of("type",a.getAnnotationType().toString(),"values",values));
    }
    return out;
  }
  public static void main(String[] args)throws Exception {
    Path root=Path.of(args[0]).toAbsolutePath();
    var compiler=ToolProvider.getSystemJavaCompiler();var diagnostics=new DiagnosticCollector<JavaFileObject>();
    var fm=compiler.getStandardFileManager(diagnostics,null,null);
    var files=Files.readAllLines(Path.of(args[1])).stream().filter(s->s.endsWith(".java")).map(s->root.resolve(s).toFile()).toList();
    var task=(JavacTask)compiler.getTask(null,fm,diagnostics,List.of("-proc:none","-classpath",args[2],"-Xlint:none","--add-exports","jdk.jfr/jdk.jfr.internal.query=ALL-UNNAMED","--add-exports","jdk.jfr/jdk.jfr.internal.util=ALL-UNNAMED"),null,fm.getJavaFileObjectsFromFiles(files));
    var units=new ArrayList<CompilationUnitTree>();task.parse().forEach(units::add);task.analyze();trees=Trees.instance(task);
    var declarations=new ArrayList<Map<String,Object>>();var references=new ArrayList<Map<String,Object>>();
    for(var unit:units)new TreePathScanner<Void,Void>() {
      String owner=null, method=null;
      String path=root.relativize(Path.of(unit.getSourceFile().toUri())).toString();
      Map<String,Object> base(Tree t,String kind,Element e) {
        var r=new LinkedHashMap<String,Object>();r.put("kind",kind);r.put("id",identity(e));r.put("file",path);
        long start=trees.getSourcePositions().getStartPosition(unit,t),end=trees.getSourcePositions().getEndPosition(unit,t);
        r.put("start",start);r.put("end",end);r.put("line",start<0?-1:unit.getLineMap().getLineNumber(start));r.put("owner",owner);r.put("annotations",annotations(e));return r;
      }
      public Void visitClass(ClassTree t,Void v) {
        String old=owner;var e=trees.getElement(getCurrentPath());owner=identity(e);var r=base(t,t.getKind().toString(),e);
        r.put("extends",t.getExtendsClause()==null?null:t.getExtendsClause().toString());r.put("implements",t.getImplementsClause().stream().map(Object::toString).toList());
        r.put("modifiers",t.getModifiers().getFlags().stream().map(Object::toString).sorted().toList());declarations.add(r);super.visitClass(t,v);owner=old;return null;
      }
      public Void visitMethod(MethodTree t,Void v) {
        String old=method;var e=trees.getElement(getCurrentPath());method=identity(e);var r=base(t,"METHOD",e);r.put("name",t.getName().toString());
        r.put("return_type",e instanceof ExecutableElement m?m.getReturnType().toString():String.valueOf(t.getReturnType()));
        var params=new ArrayList<Map<String,Object>>();
        if(e instanceof ExecutableElement m)for(var p:m.getParameters())params.add(Map.of("name",p.getSimpleName().toString(),"type",p.asType().toString(),"annotations",annotations(p)));
        r.put("parameters",params);r.put("modifiers",t.getModifiers().getFlags().stream().map(Object::toString).sorted().toList());declarations.add(r);super.visitMethod(t,v);method=old;return null;
      }
      public Void visitVariable(VariableTree t,Void v) {
        var e=trees.getElement(getCurrentPath());
        if(e!=null&&(e.getKind()==ElementKind.FIELD||e.getKind()==ElementKind.ENUM_CONSTANT)) {
          var r=base(t,"FIELD",e);r.put("name",t.getName().toString());r.put("type",e.asType().toString());
          r.put("modifiers",t.getModifiers().getFlags().stream().map(Object::toString).sorted().toList());declarations.add(r);
        }
        return super.visitVariable(t,v);
      }
      void reference(Tree t) {
        var e=trees.getElement(getCurrentPath());if(e==null)return;
        if(!(e instanceof TypeElement)&&!(e instanceof ExecutableElement)&&e.getKind()!=ElementKind.FIELD)return;
        var r=new LinkedHashMap<String,Object>();r.put("file",path);r.put("line",unit.getLineMap().getLineNumber(trees.getSourcePositions().getStartPosition(unit,t)));r.put("from",method==null?owner:method);r.put("to",identity(e));r.put("tree_kind",t.getKind().toString());references.add(r);
      }
      public Void visitIdentifier(IdentifierTree t,Void v){reference(t);return super.visitIdentifier(t,v);}
      public Void visitMemberSelect(MemberSelectTree t,Void v){reference(t);return super.visitMemberSelect(t,v);}
      public Void visitMemberReference(MemberReferenceTree t,Void v){reference(t);return super.visitMemberReference(t,v);}
      public Void visitNewClass(NewClassTree t,Void v){reference(t);return super.visitNewClass(t,v);}
    }.scan(unit,null);
    var errors=diagnostics.getDiagnostics().stream().filter(d->d.getKind()==Diagnostic.Kind.ERROR).map(d->Map.of("file",d.getSource()==null?"":root.relativize(Path.of(d.getSource().toUri())).toString(),"line",d.getLineNumber(),"code",d.getCode(),"message",d.getMessage(Locale.ROOT))).toList();
    new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of(args[3]).toFile(),Map.of("collector","JDK javac Trees; -proc:none; independent of Quill","java_version",System.getProperty("java.version"),"files",files.size(),"errors",errors,"declarations",declarations,"references",references));fm.close();
  }
}
