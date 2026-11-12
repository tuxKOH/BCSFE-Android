package io.github.tuxkoh.bcsfe;

import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;
import java.util.concurrent.Executor;

final class SponsorsDialog {
    private SponsorsDialog() {}

    static void show(AppCompatActivity activity, Executor network) {
        int padding = (int) (24 * activity.getResources().getDisplayMetrics().density);
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(padding, padding / 2, padding, 0);
        TextView note = new TextView(activity);
        note.setText(R.string.sponsors_unranked);
        body.addView(note);
        ProgressBar progress = new ProgressBar(activity);
        body.addView(progress);
        TextView status = new TextView(activity);
        status.setText(R.string.sponsors_loading);
        body.addView(status);
        ListView list = new ListView(activity);
        body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(R.string.sponsors_title)
                .setView(body).setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.sponsors_retry, null).create();
        Runnable load = () -> {
            progress.setVisibility(View.VISIBLE);
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.sponsors_loading);
            list.setVisibility(View.GONE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            network.execute(() -> {
                List<String> names;
                try { names = SponsorDirectory.fetch(); }
                catch (Exception unavailable) { names = null; }
                List<String> result = names;
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed() || !dialog.isShowing()) return;
                    progress.setVisibility(View.GONE);
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    if (result == null) { status.setText(R.string.sponsors_failed); return; }
                    status.setText(R.string.sponsors_empty);
                    status.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
                    list.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, result));
                    list.setVisibility(View.VISIBLE);
                });
            });
        };
        dialog.show();
        dialog.getWindow().setLayout(-1, (int) (activity.getResources().getDisplayMetrics().heightPixels * .8f));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> load.run());
        load.run();
    }
}
