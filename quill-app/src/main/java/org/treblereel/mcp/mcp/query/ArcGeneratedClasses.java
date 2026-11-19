package org.treblereel.mcp.mcp.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.db.ReadQueryContext;

/**
 * Classes Quarkus ArC generates at build time for application beans (X_ClientProxy, X_Bean,
 * X_Subclass, X_Observer_..., X_ProducerMethod_..._Bean). They exist in neither compiled outputs
 * nor jars, so lookups miss them; their bean is in the index and answers what they are.
 */
public final class ArcGeneratedClasses {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final Pattern MEMBER =
            Pattern.compile(
                    "(.+?)_(Observer|ProducerMethod|ProducerField)_(.+?)_[0-9a-f]{6,}(_Bean|_ClientProxy)?");
    private static final Pattern CLASS =
            Pattern.compile("(.+?)_(ClientProxy|Bean|Subclass|InterceptionSubclass)");
    private static final Map<String, String> ROLES =
            Map.of(
                    "ClientProxy",
                    "client_proxy",
                    "Bean",
                    "injectable_bean",
                    "Subclass",
                    "interception_subclass",
                    "InterceptionSubclass",
                    "interception_subclass",
                    "Observer",
                    "observer_method",
                    "ProducerMethod",
                    "injectable_bean",
                    "ProducerField",
                    "injectable_bean");
    private static final Map<String, String> MEANINGS =
            Map.of(
                    "client_proxy",
                            "Client proxy of a normal-scoped bean: each call looks up the contextual instance and delegates to it",
                    "injectable_bean",
                            "InjectableBean that creates and destroys the bean's instances",
                    "interception_subclass",
                            "Subclass that applies the interceptors and decorators bound to the bean",
                    "observer_method", "Wrapper ArC registers to call the observer method");

    private ArcGeneratedClasses() {}

    /** What an ArC-generated class name is generated for, or null when it names no indexed bean. */
    public static ObjectNode describe(ReadQueryContext reader, String name) {
        if (name == null) return null;
        String owner, role, member = null;
        Matcher matcher = MEMBER.matcher(name);
        if (matcher.matches()) {
            owner = matcher.group(1);
            member = matcher.group(3);
            role =
                    "_ClientProxy".equals(matcher.group(4))
                            ? "client_proxy"
                            : ROLES.get(matcher.group(2));
        } else if ((matcher = CLASS.matcher(name)).matches()) {
            owner = matcher.group(1);
            role = ROLES.get(matcher.group(2));
        } else return null;
        String declaring = owner, producer = member;
        var bean = reader.beans.findFirstBeanOf(declaring, producer);
        if (bean.isEmpty() && !"observer_method".equals(role)) return null;
        if ("observer_method".equals(role) && reader.symbols.findClassesByName(owner).isEmpty())
            return null;
        ObjectNode answer =
                JSON.createObjectNode()
                        .put("class", name)
                        .put("origin", "generated")
                        .put(
                                "generated_by",
                                "Quarkus ArC at build time; in neither compiled outputs nor jars")
                        .put("generated_role", role)
                        .put("meaning", MEANINGS.get(role))
                        .put("generated_for", member == null ? owner : owner + "#" + member);
        bean.ifPresent(
                row ->
                        answer.putObject("bean")
                                .put("kind", String.valueOf(row.kind()))
                                .put("scope", String.valueOf(row.scope())));
        answer.putObject("next_step")
                .put("tool", "get_symbol_details")
                .putObject("arguments")
                .put("target", owner);
        return answer;
    }
}
