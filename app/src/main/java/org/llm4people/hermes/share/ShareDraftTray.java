package org.llm4people.hermes.share;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.llm4people.hermes.Diagnostics;
import org.llm4people.hermes.R;
import org.llm4people.hermes.Ui;
import org.llm4people.hermes.camera.CapturedMedia;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Everyday-French horizontal attachment tray above the message composer.
 * Displays removable chips for captured photos, shared screenshots, PDFs, and code/text files.
 */
public final class ShareDraftTray {
    public interface Listener {
        void onChanged(List<CapturedMedia.Item> items);
    }

    private final Context context;
    private final HorizontalScrollView scroller;
    private final LinearLayout row;
    private final TextView announcement;
    private final List<CapturedMedia.Item> items = new ArrayList<>();
    private final Listener listener;

    public ShareDraftTray(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setVisibility(View.GONE);
        row = Ui.row(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(row, 14, 6);
        scroller.addView(row);

        announcement = Ui.text(context, "", 13);
        announcement.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        announcement.setVisibility(View.GONE);
    }

    public View view() { return scroller; }

    public List<CapturedMedia.Item> items() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }

    public boolean isEmpty() { return items.isEmpty(); }

    public boolean add(CapturedMedia.Item item, int maxItems) {
        if (item == null || items.size() >= maxItems) return false;
        items.add(item);
        render("Élément ajouté : " + item.name());
        return true;
    }

    public void removeAt(int index) {
        if (index < 0 || index >= items.size()) return;
        CapturedMedia.Item removed = items.remove(index);
        render("Élément retiré : " + removed.name());
    }

    public void clear() {
        if (items.isEmpty()) return;
        items.clear();
        render("Pièces jointes effacées.");
    }

    private void render(String notice) {
        row.removeAllViews();
        scroller.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        int gap = Ui.dp(context, 8);
        for (int i = 0; i < items.size(); i++) {
            int index = i;
            CapturedMedia.Item item = items.get(i);
            LinearLayout chip = Ui.row(context);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            Ui.pad(chip, 10, 6);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(context, 12));
            bg.setColor(context.getColor(R.color.surface));
            bg.setStroke(Ui.dp(context, 1), context.getColor(R.color.line));
            chip.setBackground(bg);

            if (item.kind() == CapturedMedia.Kind.IMAGE && item.thumbnailBase64() != null) {
                Bitmap preview = decodeThumb(item.thumbnailBase64());
                if (preview != null) {
                    ImageView thumb = new ImageView(context);
                    thumb.setImageBitmap(preview);
                    thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    thumb.setContentDescription("Aperçu · " + item.name());
                    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(Ui.dp(context, 36), Ui.dp(context, 36));
                    tp.setMarginEnd(gap);
                    chip.addView(thumb, tp);
                }
            }

            LinearLayout meta = Ui.column(context);
            TextView title = Ui.text(context, item.name(), 14);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            title.setMaxWidth(Ui.dp(context, 150));
            String subtitle = (item.kind() == CapturedMedia.Kind.IMAGE ? "Photo" : "Fichier")
                    + " · " + Formatter.formatShortFileSize(context, item.size());
            TextView detail = Ui.text(context, subtitle, 12);
            detail.setTextColor(context.getColor(R.color.muted));
            meta.addView(title);
            meta.addView(detail);
            chip.addView(meta);

            Button remove = Ui.button(context, "Retirer", v -> removeAt(index));
            remove.setContentDescription("Retirer " + item.name());
            remove.setMinimumHeight(Ui.dp(context, 48));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
            rp.setMarginStart(gap);
            chip.addView(remove, rp);

            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) cp.setMarginStart(gap);
            row.addView(chip, cp);
        }
        announcement.setText(notice);
        if (listener != null) listener.onChanged(items());
    }

    private static Bitmap decodeThumb(String base64) {
        try {
            byte[] raw = Base64.getDecoder().decode(base64);
            return BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (IllegalArgumentException invalid) {
            Diagnostics.debug("share", "thumb_decode_failed", invalid);
            return null;
        }
    }
}
