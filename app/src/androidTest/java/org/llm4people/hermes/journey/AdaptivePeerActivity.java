package org.llm4people.hermes.journey;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;

/** A disposable second Android task launched adjacent to Hermes by the adaptive device journey. */
public final class AdaptivePeerActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Button close = new Button(this);
        close.setText("Fermer la seconde fenêtre");
        close.setOnClickListener(v -> finishAndRemoveTask());
        setContentView(close);
    }
}
