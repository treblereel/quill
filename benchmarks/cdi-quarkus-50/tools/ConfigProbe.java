import java.util.*;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigSource;

/** Explicit in-memory sources only; never reads process environment, system properties or secrets. */
public class ConfigProbe {
  record Source(String name,int ordinal,Map<String,String> values) implements ConfigSource {
    public String getName(){return name;} public int getOrdinal(){return ordinal;}
    public Map<String,String> getProperties(){return values;} public Set<String> getPropertyNames(){return values.keySet();}
    public String getValue(String key){return values.get(key);}
  }
  public static void main(String[] args) {
    String key="cryostat.security.rbac.cache.maximum-size";
    var config=new SmallRyeConfigBuilder().withSources(new Source("application-fixture",100,Map.of(key,"1000")),new Source("explicit-override",500,Map.of(key,"25"))).build();
    var value=config.getConfigValue(key);
    if(!"25".equals(value.getValue())||!"explicit-override".equals(value.getConfigSourceName()))throw new AssertionError(value);
    System.out.println("{\"value\":25,\"winning_source\":\"explicit-override\",\"ordinal\":500,\"ambient_sources_loaded\":false}");
  }
}
