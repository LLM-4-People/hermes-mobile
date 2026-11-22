package org.llm4people.hermes;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Navigation frame shared by discussion and workspace views. */
final class WorkspaceShell {
    final LinearLayout root;
    private final Activity activity;
    private final Consumer<String> action;
    private final BooleanSupplier reading;
    private final LinearLayout header;
    private final FrameLayout body;
    private final TextView heading;
    private final Button pending;
    private final ImageButton leading;
    private final ImageButton desk;
    private String pageTitle;
    private String threadId;
    private String threadTitle;
    private boolean canTogglePanes;
    private boolean listVisible;
    private PopupMenu overflow;

    WorkspaceShell(Activity activity, Consumer<String> action, BooleanSupplier reading) {
        this.reading = reading;
        this.activity = activity;
        this.action = action;
        root = Ui.column(activity);
        root.setBackgroundColor(activity.getColor(R.color.paper));
        header = Ui.row(activity);
        Ui.pad(header, 6, 2);
        leading = icon(R.drawable.ic_threads, "Discussions");
        leading.setOnClickListener(v -> action.accept(
                pageTitle != null ? "previous" : canTogglePanes ? "toggle_discussions" : "threads"));
        header.addView(leading);
        heading = Ui.text(activity, "Discussions", 20);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        Ui.pad(heading, 6, 2);
        heading.setAccessibilityHeading(true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        desk = icon(R.drawable.ic_desk, "Atelier");
        desk.setOnClickListener(v -> action.accept("workspace"));
        header.addView(desk);
        ImageButton more = icon(R.drawable.ic_more, "Autres actions");
        more.setOnClickListener(this::menu);
        header.addView(more);
        root.addView(header);
        root.addView(Ui.divider(activity));
        pending = Ui.button(activity, "Répondre à Hermes", null);
        pending.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        pending.setVisibility(View.GONE);
        root.addView(pending);
        body = new FrameLayout(activity);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    void selected(String id, String title) {
        if (!Objects.equals(threadId, id)) dismissMenu();
        threadId = id;
        threadTitle = title;
        updateHeading();
    }

    void discussionPanes(boolean canToggle, boolean visible) {
        if (canTogglePanes == canToggle && listVisible == visible) return;
        canTogglePanes = canToggle;
        listVisible = visible;
        updateHeading();
    }

    void show(View page, String title) {
        pageTitle = title;
        updateHeading();
        if (page.getParent() instanceof ViewGroup) ((ViewGroup) page.getParent()).removeView(page);
        body.removeAllViews();
        body.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    void pendingResponse(Runnable open) {
        pending.setVisibility(open == null ? View.GONE : View.VISIBLE);
        pending.setOnClickListener(open == null ? null : v -> open.run());
    }

    View content() { return body; }

    View desk(String accountName) {
        LinearLayout list = Ui.column(activity);
        ScrollView scroll = new ScrollView(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                float density = getResources().getDisplayMetrics().density;
                int gutter = Ui.dp(activity, AdaptiveLayout.centeredGutterDp(
                        MeasureSpec.getSize(widthSpec) / density, AdaptiveLayout.READING_COLUMN_MAX_DP, 22));
                int vertical = Ui.dp(activity, 14);
                if (list.getPaddingStart() != gutter || list.getPaddingTop() != vertical) {
                    list.setPaddingRelative(gutter, vertical, gutter, vertical);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        scroll.setFillViewport(true);
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        if (threadTitle != null) {
            section(list, threadTitle);
            link(list, "Reprendre la discussion", "return");
            link(list, "Artefacts", "artifacts");
            link(list, "Modèle et outils", "models");
            link(list, "Utilisation", "stats");
        }
        section(list, "Espace de travail");
        link(list, "Mes écrans", "pages");
        link(list, "Nouvelle discussion", "new_thread");
        link(list, "Toutes les discussions", "threads");
        link(list, "Rappels", "reminders");
        section(list, accountName);
        link(list, "Notifications", "notifications");
        link(list, "Verrouiller le profil", "profiles");
        return scroll;
    }

    private void updateHeading() {
        heading.setText(pageTitle != null ? pageTitle : threadTitle == null ? "Discussions" : threadTitle);
        leading.setImageResource(pageTitle != null ? R.drawable.ic_back : R.drawable.ic_threads);
        String description = pageTitle != null ? "Revenir à l’écran précédent"
                : canTogglePanes ? (listVisible ? "Masquer les discussions" : "Afficher les discussions")
                : "Toutes les discussions";
        leading.setContentDescription(description);
        leading.setTooltipText(description);
        leading.setActivated(pageTitle == null && canTogglePanes && listVisible);
        desk.setVisibility("Atelier".equals(pageTitle) ? View.GONE : View.VISIBLE);
    }

    private ImageButton icon(int resource, String name) {
        ImageButton view = new ImageButton(activity);
        TypedValue ripple = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        view.setBackgroundResource(ripple.resourceId);
        view.setImageResource(resource);
        view.setContentDescription(name);
        view.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(activity, 48), Ui.dp(activity, 48)));
        return view;
    }

    private void menu(View anchor) {
        dismissMenu();
        PopupMenu menu = new PopupMenu(activity, anchor);
        overflow = menu;
        menu.setOnDismissListener(closed -> {
            if (overflow == closed) overflow = null;
        });
        item(menu, "Nouvelle discussion", "new_thread");
        if (threadTitle != null) {
            if (reading.getAsBoolean()) item(menu, activity.getString(R.string.read_stop_label), "stop_reading");
            else item(menu, activity.getString(R.string.read_reply_label), "read_reply");
            item(menu, activity.getString(R.string.instrument_create), "create_instrument");
            item(menu, "Vue de discussion d’origine", "native_thread");
            if (pageTitle == null) {
                item(menu, "Joindre une image…", "attach_photo");
                if (org.llm4people.hermes.camera.ThreadCameraCapture.isAvailable(activity)) {
                    item(menu, "Prendre une photo…", "capture_camera");
                }
                item(menu, "Position pour ce message…", "nearby_area");
            }
            item(menu, "Renommer la discussion", "rename");
            item(menu, "Artefacts", "artifacts");
            item(menu, "Modèle et outils", "models");
            item(menu, "Utilisation", "stats");
        }
        item(menu, "Mes écrans", "pages");
        item(menu, "Rappels", "reminders");
        item(menu, "Rétablir l’atelier", "restore");
        item(menu, "Verrouiller le profil", "profiles");
        menu.show();
    }

    private void item(PopupMenu menu, String label, String id) {
        menu.getMenu().add(label).setOnMenuItemClickListener(item -> {
            if (overflow != menu) return false;
            action.accept(id);
            return true;
        });
    }

    void dismissMenu() {
        PopupMenu previous = overflow;
        overflow = null;
        if (previous != null) previous.dismiss();
    }

    private void section(LinearLayout list, String name) {
        TextView section = Ui.text(activity, name, 24);
        section.setTypeface(Typeface.SERIF);
        section.setPaddingRelative(0, Ui.dp(activity, 18), 0, Ui.dp(activity, 10));
        section.setAccessibilityHeading(true);
        list.addView(section);
    }

    private void link(LinearLayout list, String label, String id) {
        LinearLayout row = Ui.row(activity);
        row.setMinimumHeight(Ui.dp(activity, 53));
        row.setOnClickListener(v -> action.accept(id));
        row.setFocusable(true);
        TypedValue ripple = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        row.setContentDescription(label);
        TextView text = Ui.text(activity, label, 16);
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView arrow = new ImageView(activity);
        arrow.setImageResource(R.drawable.ic_navigate_forward);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow, Ui.dp(activity, 18), Ui.dp(activity, 18));
        list.addView(row);
        list.addView(Ui.divider(activity));
    }
}
