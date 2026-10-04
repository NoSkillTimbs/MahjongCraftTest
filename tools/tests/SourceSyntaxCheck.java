import javax.tools.*;
import com.sun.source.util.JavacTask;
import java.nio.file.*;
import java.util.*;

/** Syntax only: deliberately does not pretend that missing Minecraft APIs have been type-checked. */
public final class SourceSyntaxCheck {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        if(compiler==null)throw new IllegalStateException("A JDK compiler module is required");
        List<Path> paths;
        try(var stream=Files.walk(Path.of("src/main/java"))){paths=stream.filter(p->p.toString().endsWith(".java")).toList();}
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        try(var manager=compiler.getStandardFileManager(diagnostics,null,null)){
            JavacTask task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none"),null,manager.getJavaFileObjectsFromPaths(paths));
            task.parse();
        }
        boolean fail=false;
        for(var diagnostic:diagnostics.getDiagnostics())if(diagnostic.getKind()==Diagnostic.Kind.ERROR){System.err.println(diagnostic);fail=true;}
        if(fail)throw new AssertionError("Java syntax errors");
        System.out.println("PASS: Java syntax parsing, "+paths.size()+" files (not Minecraft API compilation)");
    }
}
