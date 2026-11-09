package org.treblereel.mcp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class AffectedTestRowsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String DIRECT = """
            {"class":"io.cryostat.security.rbac.PermissionMapperTest","file":"src/test/java/io/cryostat/security/rbac/PermissionMapperTest.java",
             "score":100,"confidence":"high","static":true,"evidence":["static_direct","historical_co_change"],"dependency_depth":1,
             "dependency_path":["io.cryostat.security.rbac.PermissionMapper","io.cryostat.security.rbac.PermissionMapperTest"],
             "dependency_kinds":["CLASS_REFERENCE"],"co_change_count":1,
             "reasons":["Depends on io.cryostat.security.rbac.PermissionMapper at depth 1","Co-changed 1 time(s) with io.cryostat.security.rbac.PermissionMapper"]}""";
    private static final String TRANSITIVE = """
            {"class":"io.cryostat.security.rbac.AgentGatewaySecretUnconfiguredTest","file":"src/test/java/io/cryostat/security/rbac/AgentGatewaySecretUnconfiguredTest.java",
             "score":70,"confidence":"medium","static":true,"evidence":["static_transitive"],"dependency_depth":3,
             "dependency_path":["io.cryostat.security.rbac.PermissionMapper","io.cryostat.security.rbac.SsarAuthorizer","io.cryostat.security.rbac.RbacHttpAuthenticationMechanism","io.cryostat.security.rbac.AgentGatewaySecretUnconfiguredTest"],
             "dependency_kinds":["CALLS","CALLS","CLASS_REFERENCE"],"co_change_count":0,
             "reasons":["Depends on io.cryostat.security.rbac.PermissionMapper at depth 3"]}""";

    /** V22 CQ-049: 20 ranked tests were 10 KB, stated in the plan, the verification and the directive. */
    @Test
    void aTestRowKeepsWhatRanksItAndDropsWhatRestatesIt() throws Exception {
        assertEquals("{\"class\":\"io.cryostat.security.rbac.PermissionMapperTest\",\"score\":100,\"confidence\":\"high\",\"co_changed\":1}",
                AffectedTestRows.row(JSON.readTree(DIRECT)).toString());
        assertEquals("{\"class\":\"io.cryostat.security.rbac.AgentGatewaySecretUnconfiguredTest\",\"score\":70,\"confidence\":\"medium\","
                        + "\"via\":[\"io.cryostat.security.rbac.SsarAuthorizer\",\"io.cryostat.security.rbac.RbacHttpAuthenticationMechanism\"]}",
                AffectedTestRows.row(JSON.readTree(TRANSITIVE)).toString());
    }

    @Test
    void anUnconventionalFileAndAHistoryOnlyLinkAreStated() throws Exception {
        ObjectNode test = (ObjectNode) JSON.readTree(DIRECT);
        test.put("file", "it/src/test/java/io/cryostat/security/rbac/PermissionMapperTest.java").put("static", false);
        var row = AffectedTestRows.row(test);
        assertEquals("it/src/test/java/io/cryostat/security/rbac/PermissionMapperTest.java", row.path("file").asText(), row.toString());
        assertTrue(row.has("static") && !row.path("static").asBoolean(), row.toString());
    }

    @Test
    void everyTestListInAChangeSessionAnswerIsCompacted() throws Exception {
        ObjectNode answer = (ObjectNode) JSON.readTree("{\"plan\":{\"test_plan\":{\"tests\":[" + DIRECT + "," + TRANSITIVE + "]}},"
                + "\"verification\":{\"test_evidence\":{\"tests\":[" + DIRECT + "]}},"
                + "\"directive\":{\"primary_action\":{\"evidence_snapshot\":{\"tests\":[" + TRANSITIVE + "]}}}}");
        int before = answer.toString().length();
        AffectedTestRows.compactIn(answer);
        for (String path : new String[] {"/plan/test_plan/tests/0", "/verification/test_evidence/tests/0",
                "/directive/primary_action/evidence_snapshot/tests/0"}) {
            assertTrue(answer.at(path).has("score") && !answer.at(path).has("reasons") && !answer.at(path).has("dependency_path"),
                    path + " " + answer.at(path));
        }
        assertTrue(answer.toString().length() < before / 3, before + " -> " + answer.toString().length());
    }
}
