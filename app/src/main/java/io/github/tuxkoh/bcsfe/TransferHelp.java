package io.github.tuxkoh.bcsfe;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.button.MaterialButton;

final class TransferHelp {
    private TransferHelp() {}

    static void show(Activity activity, boolean use) {
        String[] files = use
                ? new String[]{"1.进入账号转移.jpg","2.进入机种变更.jpg","3a.进入使用转移码界面.jpg","4a.使用转移码.jpg"}
                : new String[]{"1.进入账号转移.jpg","2.进入机种变更.jpg","3.进入转移码转出界面.jpg","4.将游戏存档上传并产出转移码.jpg","5.转移码.jpg"};
        String[] descriptions=activity.getResources().getStringArray(use?R.array.transfer_help_use_steps:R.array.transfer_help_get_steps);
        WebView page=new WebView(activity);
        page.getSettings().setJavaScriptEnabled(false);
        page.getSettings().setAllowFileAccess(false);
        page.getSettings().setAllowContentAccess(false);
        page.getSettings().setBlockNetworkLoads(true);
        page.getSettings().setBuiltInZoomControls(true);
        page.getSettings().setDisplayZoomControls(false);
        StringBuilder html=new StringBuilder("<html><head><meta name='viewport' content='width=device-width, initial-scale=1'><style>body{margin:0;padding:16px;color:#102A2E;background:white;font:16px sans-serif}section{margin-bottom:28px}p{line-height:1.6}img{width:100%;height:auto}h2{font-size:20px;color:#0B6B5B}</style></head><body>");
        for(int i=0;i<files.length;i++) {
            html.append("<section><h2>").append(i+1).append("</h2><p>")
                    .append(android.text.TextUtils.htmlEncode(descriptions[i])).append("</p><img src='file:///android_asset/transfer_help/")
                    .append(android.net.Uri.encode(files[i])).append("'></section>");
        }
        page.loadDataWithBaseURL("file:///android_asset/transfer_help/",html.append("</body></html>").toString(),"text/html","UTF-8",null);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(use?R.string.transfer_help_use:R.string.transfer_help_get)
                .setView(page).setPositiveButton(R.string.close,null).create();
        dialog.setOnDismissListener(d->{page.stopLoading();page.destroy();});
        dialog.show();
        dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,(int)(activity.getResources().getDisplayMetrics().heightPixels*.85f));
    }

    static void showIntro(Activity activity,View target) {
        android.content.SharedPreferences prefs=activity.getSharedPreferences("onboarding",Activity.MODE_PRIVATE);
        if(prefs.getBoolean("transfer_intro_seen",false)||activity.isFinishing()||!target.isShown())return;
        ViewGroup decor=(ViewGroup)activity.getWindow().getDecorView();
        FrameLayout overlay=new FrameLayout(activity);
        overlay.setClickable(true);
        float density=activity.getResources().getDisplayMetrics().density;
        Rect hole=new Rect();
        target.getGlobalVisibleRect(hole);
        int[] origin=new int[2];decor.getLocationOnScreen(origin);hole.offset(-origin[0],-origin[1]);
        View shade=new View(activity) {
            final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                Path mask=new Path();mask.setFillType(Path.FillType.EVEN_ODD);
                mask.addRect(0,0,getWidth(),getHeight(),Path.Direction.CW);
                mask.addRoundRect(hole.left,hole.top,hole.right,hole.bottom,16*density,16*density,Path.Direction.CW);
                paint.setColor(0xB3000000);canvas.drawPath(mask,paint);
                float x=hole.centerX(),y=hole.bottom+12*density;
                paint.setColor(0xFFFFFFFF);paint.setStrokeWidth(3*density);
                canvas.drawLine(x,y+32*density,x,y,paint);
                canvas.drawLine(x,y,x-8*density,y+10*density,paint);
                canvas.drawLine(x,y,x+8*density,y+10*density,paint);
            }
        };
        overlay.addView(shade,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout bubble=new LinearLayout(activity);bubble.setOrientation(LinearLayout.VERTICAL);
        int padding=(int)(16*density);bubble.setPadding(padding,padding,padding,padding);
        android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
        background.setColor(activity.getColor(R.color.surface));background.setCornerRadius(16*density);bubble.setBackground(background);
        TextView text=new TextView(activity);text.setText(R.string.transfer_intro);text.setTextSize(18);text.setTextColor(activity.getColor(R.color.ink));bubble.addView(text);
        Runnable dismiss=()->{prefs.edit().putBoolean("transfer_intro_seen",true).apply();decor.removeView(overlay);};
        MaterialButton close=new MaterialButton(activity);close.setText(R.string.close);close.setOnClickListener(v->dismiss.run());bubble.addView(close);
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-1,-2);bp.leftMargin=padding;bp.rightMargin=padding;bp.topMargin=hole.bottom+(int)(52*density);
        android.widget.ScrollView bubbleScroll=new android.widget.ScrollView(activity);
        bubbleScroll.setFillViewport(false);bubbleScroll.addView(bubble);
        overlay.addView(bubbleScroll,bp);
        View hit=new View(activity);FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(hole.width(),hole.height());hp.leftMargin=hole.left;hp.topMargin=hole.top;
        hit.setContentDescription(activity.getString(R.string.receive_transfer));hit.setOnClickListener(v->{dismiss.run();target.performClick();});overlay.addView(hit,hp);
        overlay.getViewTreeObserver().addOnPreDrawListener(()->{
            if(!target.isShown()){decor.removeView(overlay);return true;}
            Rect current=new Rect();target.getGlobalVisibleRect(current);
            decor.getLocationOnScreen(origin);current.offset(-origin[0],-origin[1]);
            if(!current.equals(hole)) {
                hole.set(current);hp.leftMargin=hole.left;hp.topMargin=hole.top;hp.width=hole.width();hp.height=hole.height();
                hit.setLayoutParams(hp);shade.invalidate();
            }
            int available=overlay.getHeight()-hole.bottom-(int)(68*density);
            int top=hole.bottom+(int)(52*density);
            if(available<(int)(100*density)){top=padding;available=Math.max(1,hole.top-padding*2);}
            bubble.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1,overlay.getWidth()-padding*2),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
            int height=Math.min(bubble.getMeasuredHeight(),Math.max(1,available));
            if(bp.topMargin!=top||bp.height!=height){bp.topMargin=top;bp.height=height;bubbleScroll.setLayoutParams(bp);}
            return true;
        });
        decor.addView(overlay,new ViewGroup.LayoutParams(-1,-1));
        overlay.setAlpha(0);overlay.animate().alpha(1).setDuration(220).start();
    }
}
