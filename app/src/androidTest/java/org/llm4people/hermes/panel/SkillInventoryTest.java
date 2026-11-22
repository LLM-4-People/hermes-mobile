package org.llm4people.hermes.panel;

import junit.framework.TestCase;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.List;

public final class SkillInventoryTest extends TestCase {
    public void testSearchPreservesLoadableNamesAndGatewayOrder() throws Exception {
        List<SkillInventory> skills = SkillInventory.parse(new JSONObject("""
                {"object":"list","data":[
                  {"name":"personnel/revue","description":"Vérifier un résumé","category":null},
                  {"name":"travail/revue","description":"Relire le code","category":"Qualité"},
                  {"name":"plugin:release","description":"","category":"Équipe"}]}
                """));
        assertEquals(3, SkillInventory.search(skills, "").size());
        assertEquals("personnel/revue", skills.get(0).name);
        assertEquals("", skills.get(0).category);
        assertEquals("travail/revue", SkillInventory.search(skills, "QUALITE\u202fcode").get(0).name);
        assertEquals("personnel/revue", SkillInventory.search(skills, "resume").get(0).name);
        assertEquals("plugin:release", SkillInventory.search(skills, "equipe").get(0).name);
        assertTrue(SkillInventory.search(skills, "autre").isEmpty());
        assertTrue(SkillInventory.parse(new JSONObject("{\"object\":\"list\",\"data\":[]}")).isEmpty());
    }

    public void testUnusableLocatorLeavesNeighborsAndTheirExactNamesAvailable() throws Exception {
        List<SkillInventory> skills = SkillInventory.parse(new JSONObject("""
                {"object":"list","data":[
                  {"name":"editing/review"},
                  {"name":"review\\n","description":"Folded YAML name","category":null},
                  null,{"name":42},{"name":"bad-category","category":[]},
                  {"name":" team/review ","description":"Exact name","category":"editing"}]}
                """));
        assertEquals(2, skills.size());
        assertEquals("editing/review", skills.get(0).name);
        assertEquals(" team/review ", skills.get(1).name);
        assertEquals(2, SkillInventory.search(skills, "review").size());
    }

    public void testMalformedPayloadIsDistinctFromAnEmptyCatalog() throws Exception {
        for (String invalid : new String[]{"{}", "{\"object\":\"error\",\"data\":[]}", "{\"data\":{}}", "{\"data\":[null]}", "{\"data\":[{}]}",
                "{\"data\":[{\"name\":12}]}", "{\"data\":[{\"name\":\"ligne\\nsuivante\"}]}"}) {
            try {
                SkillInventory.parse(new JSONObject(invalid));
                fail(invalid);
            } catch (JSONException expected) {}
        }
    }
}
