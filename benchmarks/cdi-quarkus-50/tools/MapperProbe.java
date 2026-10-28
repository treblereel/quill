package io.cryostat.security.rbac;

import java.lang.reflect.Proxy;
import java.util.*;

/** Bounded behavioral oracle; no Quarkus startup, database, network or ambient config. */
public class MapperProbe {
  static RbacConfig config(Map<String,String> mappings, String read, String fallback) {
    return (RbacConfig)Proxy.newProxyInstance(RbacConfig.class.getClassLoader(),new Class<?>[]{RbacConfig.class},(p,m,a)->switch(m.getName()) {
      case "permissions" -> mappings;
      case "defaultReadPermission" -> Optional.ofNullable(read);
      case "defaultWritePermission", "defaultDeletePermission" -> Optional.empty();
      case "defaultPermission" -> Optional.ofNullable(fallback);
      default -> throw new AssertionError("Unexpected config access: "+m);
    });
  }
  static int count=0;
  static void check(Map<String,String> mappings,String read,String fallback,String input,String expected) {
    PermissionMapper mapper=new PermissionMapper();mapper.config=config(mappings,read,fallback);
    String actual;
    try { actual=mapper.resolve(input).map(k->k.resource()+"/"+k.subresource()+":"+k.verb()).orElse("empty"); }
    catch(IllegalArgumentException e){actual="IllegalArgumentException";}
    if(!expected.equals(actual))throw new AssertionError(input+": "+expected+" != "+actual);count++;
  }
  public static void main(String[] args) {
    check(Map.of("targets.read","pods:get"),null,null,"targets:read","pods/:get");
    check(Map.of("a.read","pods/exec:create"),null,null,"a:read","pods/exec:create");
    check(Map.of(),"pods:get","pods/exec:create","unknown:read","pods/:get");
    check(Map.of(),"pods:get","pods/exec:create","unknown:action","pods/exec:create");
    check(Map.of(),null,null,"unknown:action","empty");
    check(Map.of(),null,null,"","empty");check(Map.of(),null,null,"   ","empty");check(Map.of(),null,null,null,"empty");
    check(Map.of("a.read","pods:get"),"pods/exec:create","nodes:delete","a:read","pods/:get");
    check(Map.of("a.read","bad-format"),null,null,"a:read","IllegalArgumentException");
    check(Map.of("a.read",":get"),null,null,"a:read","IllegalArgumentException");
    check(Map.of("a.read","pods:"),null,null,"a:read","IllegalArgumentException");
    System.out.println("{\"behavior_cases\":"+count+",\"passed\":"+count+",\"application_started\":false,\"network_used\":false}");
  }
}
