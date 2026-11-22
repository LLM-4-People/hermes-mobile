package org.llm4people.hermes.artifact;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;

/** Renders only local components and invokes the host's typed operations after a user gesture. */
final class WorkspaceBlocks {
    static void add(LinearLayout parent, WorkspaceLayout layout, WorkspaceLayout.Block block,
                    Activity activity, ArtifactDesk.Host host) {
        Context c = parent.getContext();
        LinearLayout group = Ui.column(c);
        Ui.pad(group, 16, "compact".equals(layout.density) ? 5 : 12);
        if ("link".equals(block.type)) {
            action(group, block.title, host == null ? null : () ->
                    DeskDialogs.navigate(activity, () -> host.openWorkspace(block.page)));
        } else {
            TextView heading = Ui.text(c, block.title, 18);
            heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            heading.setAccessibilityHeading(true);
            group.addView(heading);
            switch (block.type) {
                case "section":
                    if (!block.note.isEmpty()) group.addView(Ui.text(c, block.note,16));
                    for (String id : block.actions) action(group, WorkspaceLayout.ACTIONS.get(id),
                            host == null ? null : () -> DeskDialogs.navigate(activity, () -> host.onAction(id)));
                    break;
                case "value":
                    Value value = new Value(c, block.source);
                    if (host != null) value.refresh(host);
                    group.addView(value);
                    break;
                case "form":
                    WorkspaceForms.mount(group, block, activity, host);
                    break;
                default: throw new IllegalArgumentException("Unknown layout block");
            }
        }
        parent.addView(group);
        parent.addView(Ui.divider(c));
    }

    private static void action(LinearLayout group, String label, Runnable action) {
        Context c = group.getContext();
        if (action == null) {
            TextView sample = Ui.text(c,label,16);
            sample.setTextColor(c.getColor(R.color.accent));
            Ui.pad(sample,8,6);
            group.addView(sample);
        } else {
            Button button = Ui.button(c,label,v -> action.run());
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            group.addView(button);
        }
    }

    static String sourceLabel(String source) {
        switch (source) {
            case "profile.label": return "Nom du profil";
            case "thread.title": return "Titre de la conversation";
            case "thread.messageCount": return "Nombre de messages";
            default: throw new IllegalArgumentException("Unknown value source");
        }
    }

    /** Mark and update only our bindings; writing a form never triggers a rebuild. */
    private static final class Value extends TextView {
        final String source;
        Value(Context c, String source) {
            super(c);
            this.source = source;
            setTextSize(16);
            setTextColor(c.getColor(R.color.muted));
            setText(sourceLabel(source));
        }
        void refresh(ArtifactDesk.Host host) {
            String text = host.workspaceValue(source);
            if (text == null || text.isEmpty()) text = "Indisponible";
            if (text.length() > 240) text = text.substring(0,240) + "…";
            if (!text.contentEquals(getText())) setText(text);
        }

    }

    static void refresh(View root, ArtifactDesk.Host host) {
        if (root instanceof Value) ((Value)root).refresh(host);
        else if (root instanceof ViewGroup && !(root instanceof android.widget.AdapterView)) {
            ViewGroup g = (ViewGroup)root;
            for (int i=0; i<g.getChildCount(); i++) refresh(g.getChildAt(i), host);
        }
    }
}
