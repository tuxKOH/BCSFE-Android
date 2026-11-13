package io.github.tuxkoh.bcsfe;

import android.widget.ArrayAdapter;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import io.github.tuxkoh.bcsfe.core.SaveDocument;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Keep the selected cat and its current values visible between field edits. */
final class IndividualCatEditor {
    interface LevelInput {
        void show(String label, int current, IntConsumer change, int minimum, int maximum);
    }

    private final AppCompatActivity activity;
    private final SaveDocument document;
    private final int cat;
    private final String title;
    private final LevelInput levelInput;
    private final Runnable applied;
    private final Consumer<RuntimeException> failed;
    private final String[] labels;
    private final ArrayAdapter<String> rows;

    IndividualCatEditor(AppCompatActivity activity, SaveDocument document, int cat, String title,
                        LevelInput levelInput, Runnable applied, Consumer<RuntimeException> failed) {
        this.activity = activity; this.document = document; this.cat = cat; this.title = title;
        this.levelInput = levelInput; this.applied = applied; this.failed = failed;
        labels = activity.getResources().getStringArray(R.array.cat_detail_labels);
        rows = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, new ArrayList<>());
    }

    void show() {
        refresh();
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title).setAdapter(rows, null)
                .setNegativeButton(R.string.close, null).create();
        dialog.show();
        // Replacing the builder's item listener prevents its automatic dismissal.
        dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
            try {
                if (position == 0) apply(() -> document.setCatUnlocked(cat, !document.catUnlocked(cat)));
                else if (position == 1) levelInput.show(labels[1], document.catBaseLevel(cat), value -> {
                    document.setCatBaseLevel(cat, value); refresh();
                }, 1, 60);
                else if (position == 2) levelInput.show(labels[2], document.catPlusLevel(cat), value -> {
                    document.setCatPlusLevel(cat, value); refresh();
                }, 0, document.catMaxPlusLevel(cat));
                else if (position == 3) chooseForm();
                else new AlertDialog.Builder(activity).setTitle(R.string.cat_reset_title).setMessage(R.string.cat_reset_confirm)
                        .setNegativeButton(R.string.close, null)
                        .setPositiveButton(R.string.cat_reset_action, (d, which) -> apply(() -> document.resetCat(cat))).show();
            } catch (RuntimeException error) { failed.accept(error); }
        });
    }

    private void chooseForm() {
        new AlertDialog.Builder(activity).setTitle(R.string.cat_current_form_title)
                .setSingleChoiceItems(R.array.cat_form_stages, document.catCurrentForm(cat), (dialog, which) -> {
                    if (apply(() -> document.setCatCurrentForm(cat, which))) dialog.dismiss();
                }).setNegativeButton(R.string.close, null).show();
    }

    private boolean apply(Runnable change) {
        try { change.run(); refresh(); applied.run(); return true; }
        catch (RuntimeException error) { failed.accept(error); return false; }
    }

    private void refresh() {
        rows.setNotifyOnChange(false);
        rows.clear();
        rows.add(labels[0] + ": " + activity.getString(document.catUnlocked(cat) ? R.string.yes : R.string.no));
        rows.add(labels[1] + ": " + document.catBaseLevel(cat));
        rows.add(labels[2] + ": " + document.catPlusLevel(cat));
        rows.add(activity.getString(R.string.cat_current_form_label, document.catCurrentForm(cat)));
        rows.add(labels[3]);
        rows.notifyDataSetChanged();
    }
}
