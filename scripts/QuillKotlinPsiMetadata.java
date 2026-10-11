import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.jar.JarFile;

/** Register every PSI AST constructor, including syntax absent from tracing fixtures. */
public class QuillKotlinPsiMetadata {
    public static void main(String[] args) throws Exception {
        Class<?> ast = Class.forName("org.jetbrains.kotlin.com.intellij.lang.ASTNode");
        Path jar = Path.of(ast.getProtectionDomain().getCodeSource().getLocation().toURI());
        var records = new ArrayList<Map<String, Object>>();
        try (var input = new JarFile(jar.toFile())) {
            for (String name : input.stream().map(entry -> entry.getName())
                    .filter(name -> name.startsWith("org/jetbrains/kotlin/psi/Kt")
                            && name.endsWith(".class") && !name.contains("$"))
                    .sorted().toList()) {
                Class<?> type = Class.forName(name.substring(0, name.length() - 6).replace('/', '.'),
                        false, ast.getClassLoader());
                try {
                    type.getDeclaredConstructor(ast);
                    records.add(Map.of("name", type.getName(), "methods", List.of(
                            Map.of("name", "<init>", "parameterTypes", List.of(ast.getName())))));
                } catch (NoSuchMethodException ignored) {
                    // Interfaces, helpers, and PSI nodes constructed without reflection.
                }
            }
        }
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(System.out, records);
    }
}
