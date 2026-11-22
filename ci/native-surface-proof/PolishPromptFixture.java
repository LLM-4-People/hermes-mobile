package org.llm4people.hermes.thread;

import java.lang.reflect.Field;
import java.util.Collections;
import org.json.*;
import org.llm4people.hermes.MainActivity;

public final class PolishPromptFixture {
    private final RunDecisions decisions;
    public PolishPromptFixture(MainActivity activity) throws Exception {
        Field threads=MainActivity.class.getDeclaredField("threads");
        threads.setAccessible(true);
        ThreadWorkspace page=(ThreadWorkspace) threads.get(activity);
        page.journal.observe("relecture-fixture");
        decisions=new RunDecisions(page);
        decisions.observe(RunAction.capture(page.journal,page::token));
    }
    public void show(String kind) throws Exception {
        JSONObject prompt=new JSONObject().put("kind",kind.equals("approval") ? kind : "clarify")
            .put("prompt_id",kind).put("execution_generation",1);
        if (kind.equals("approval")) prompt.put("description","world/other-writable permissions")
            .put("command","chmod 666 partage-demo.txt").put("choices",new JSONArray().put("once").put("deny"));
        else {
            prompt.put("question",kind.equals("free") ? "Quel résultat souhaitez-vous obtenir en priorité ?" :
                "Quelle partie de la fonction de calcul des remises souhaitez-vous vérifier en premier ?");
            if (!kind.equals("free")) prompt.put("choices",new JSONArray().put("Les arrondis").put("Les cas limites"));
        }
        decisions.present(Collections.singletonList(prompt));
    }
}
