package app.webview;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * Aplicativo de uma tela so: abre o site configurado em tela cheia,
 * sem barra de endereco. Funciona no celular (toque) e na TV box
 * (controle remoto, com um cursor que anda pelas setas).
 */
public class MainActivity extends Activity {

    private static final int PEDIDO_ARQUIVO = 1;

    private FrameLayout raiz;
    private WebView web;
    private Cursor cursor;
    private String site;
    private boolean modoTv;

    // Video em tela cheia (ex: botao de tela cheia do YouTube)
    private View telaCheia;
    private WebChromeClient.CustomViewCallback telaCheiaCallback;
    private WebChromeClient chrome;

    // Envio de arquivos (botao "escolher arquivo" dos sites)
    private ValueCallback<Uri[]> escolhaArquivo;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable esconderCursor = new Runnable() {
        @Override public void run() { if (cursor != null) cursor.setVisibility(View.INVISIBLE); }
    };

    @Override
    protected void onCreate(Bundle estadoSalvo) {
        super.onCreate(estadoSalvo);
        site = getString(R.string.site);
        modoTv = detectarTv();

        raiz = new FrameLayout(this);
        raiz.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        raiz.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cursor = new Cursor(this);
        cursor.setVisibility(modoTv ? View.VISIBLE : View.INVISIBLE);
        raiz.addView(cursor, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(raiz);
        configurarWeb();

        if (estadoSalvo == null || web.restoreState(estadoSalvo) == null) {
            web.loadUrl(site);
        }
        esconderBarras();
        if (modoTv) handler.postDelayed(esconderCursor, 6000);
    }

    private boolean detectarTv() {
        UiModeManager um = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        if (um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        return !getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    private void configurarWeb() {
        WebSettings c = web.getSettings();
        c.setJavaScriptEnabled(true);
        c.setDomStorageEnabled(true);
        c.setDatabaseEnabled(true);
        c.setLoadWithOverviewMode(true);
        c.setUseWideViewPort(true);
        c.setSupportZoom(true);
        c.setBuiltInZoomControls(true);
        c.setDisplayZoomControls(false);
        c.setMediaPlaybackRequiresUserGesture(false);
        c.setJavaScriptCanOpenWindowsAutomatically(true);
        c.setSupportMultipleWindows(false); // links "abrir em nova aba" abrem aqui mesmo
        if (Build.VERSION.SDK_INT >= 21) {
            c.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        // Faz os sites tratarem o app como um navegador normal
        String ua = c.getUserAgentString();
        if (ua != null) c.setUserAgentString(ua.replace("; wv", ""));

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) cookies.setAcceptThirdPartyCookies(web, true);

        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return tratarLink(r.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return tratarLink(url);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (Build.VERSION.SDK_INT >= 23 && r.isForMainFrame()) mostrarSemInternet();
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView v, int codigo, String desc, String url) {
                if (Build.VERSION.SDK_INT < 23) mostrarSemInternet();
            }
        });

        chrome = new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (telaCheia != null) { callback.onCustomViewHidden(); return; }
                telaCheia = view;
                telaCheiaCallback = callback;
                raiz.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                cursor.setVisibility(View.INVISIBLE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                esconderBarras();
            }

            @Override
            public void onHideCustomView() {
                if (telaCheia == null) return;
                raiz.removeView(telaCheia);
                telaCheia = null;
                web.setVisibility(View.VISIBLE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (telaCheiaCallback != null) telaCheiaCallback.onCustomViewHidden();
                telaCheiaCallback = null;
                esconderBarras();
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (escolhaArquivo != null) escolhaArquivo.onReceiveValue(null);
                escolhaArquivo = callback;
                try {
                    startActivityForResult(params.createIntent(), PEDIDO_ARQUIVO);
                } catch (ActivityNotFoundException e) {
                    escolhaArquivo = null;
                    return false;
                }
                return true;
            }
        };
        web.setWebChromeClient(chrome);

        // Downloads: entrega para o navegador do aparelho baixar
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String ua, String cd, String mime, long tam) {
                abrirFora(Uri.parse(url));
            }
        });
    }

    /** Links normais abrem no app; WhatsApp, telefone, mapas etc. abrem no app certo. */
    private boolean tratarLink(String url) {
        Uri u = Uri.parse(url);
        String esquema = u.getScheme();
        if (esquema == null) return false;
        esquema = esquema.toLowerCase();
        if (esquema.equals("http") || esquema.equals("https") || esquema.equals("about")
                || esquema.equals("data") || esquema.equals("blob") || esquema.equals("javascript")) {
            return false;
        }
        if (esquema.equals("intent")) {
            try {
                Intent i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                i.setComponent(null);
                i.setSelector(null);
                try {
                    startActivity(i);
                } catch (ActivityNotFoundException e) {
                    String alternativo = i.getStringExtra("browser_fallback_url");
                    if (alternativo != null) web.loadUrl(alternativo);
                }
            } catch (Exception ignorado) { }
            return true;
        }
        abrirFora(u);
        return true;
    }

    private void abrirFora(Uri u) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (Exception ignorado) { }
    }

    private void mostrarSemInternet() {
        String link = TextUtils.htmlEncode(site);
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{background:#111;color:#eee;font-family:sans-serif;display:flex;"
                + "flex-direction:column;align-items:center;justify-content:center;height:90vh;text-align:center}"
                + "a{margin-top:24px;padding:16px 32px;background:#2a7de1;color:#fff;border-radius:10px;"
                + "text-decoration:none;font-size:20px}</style></head><body>"
                + "<h2>Sem conexão com a internet</h2><p>Verifique o Wi-Fi e tente de novo.</p>"
                + "<a href='" + link + "'>Tentar de novo</a></body></html>";
        web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    @SuppressWarnings("deprecation")
    private void esconderBarras() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean temFoco) {
        super.onWindowFocusChanged(temFoco);
        if (temFoco) esconderBarras();
    }

    // ---------- Controle remoto da TV (cursor nas setas) ----------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (telaCheia == null) {
            int k = e.getKeyCode();
            boolean seta = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                    || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
            boolean ok = k == KeyEvent.KEYCODE_DPAD_CENTER
                    || (modoTv && (k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER));
            if (seta || ok) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    mostrarCursor();
                    if (seta) cursor.mover(k, e.getRepeatCount());
                    else if (e.getRepeatCount() == 0) clicar(cursor.x, cursor.y);
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void mostrarCursor() {
        cursor.setVisibility(View.VISIBLE);
        handler.removeCallbacks(esconderCursor);
        handler.postDelayed(esconderCursor, 6000);
    }

    private void clicar(float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent desce = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        desce.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(desce);
        desce.recycle();
        MotionEvent sobe = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0);
        sobe.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(sobe);
        sobe.recycle();
    }

    /** Rola a parte do site que esta embaixo do cursor (ou a pagina inteira). */
    @SuppressWarnings("deprecation")
    private void rolarPagina(float px, float py, float dx, float dy) {
        float escala = web.getScale();
        if (escala <= 0) escala = getResources().getDisplayMetrics().density;
        String js = "(function(x,y,dx,dy){"
                + "var el=document.elementFromPoint(x,y);"
                + "while(el&&el!==document.body&&el!==document.documentElement){"
                + "var s=getComputedStyle(el);"
                + "if(dy&&/(auto|scroll|overlay)/.test(s.overflowY)&&el.scrollHeight>el.clientHeight){"
                + "var a=el.scrollTop;el.scrollBy(0,dy);if(el.scrollTop!==a)return;}"
                + "if(dx&&/(auto|scroll|overlay)/.test(s.overflowX)&&el.scrollWidth>el.clientWidth){"
                + "var b=el.scrollLeft;el.scrollBy(dx,0);if(el.scrollLeft!==b)return;}"
                + "el=el.parentElement;}"
                + "(document.scrollingElement||document.body).scrollBy(dx,dy);"
                + "})(" + Math.round(px / escala) + "," + Math.round(py / escala) + ","
                + Math.round(dx / escala) + "," + Math.round(dy / escala) + ");";
        web.evaluateJavascript(js, null);
    }

    /** Desenha a bolinha do cursor por cima do site. */
    private class Cursor extends View {
        float x = -1, y = -1;
        private final float raio;
        private final Paint preenchimento = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borda = new Paint(Paint.ANTI_ALIAS_FLAG);

        Cursor(Context ctx) {
            super(ctx);
            float d = ctx.getResources().getDisplayMetrics().density;
            raio = 9 * d;
            preenchimento.setColor(Color.WHITE);
            preenchimento.setAlpha(230);
            borda.setColor(Color.BLACK);
            borda.setStyle(Paint.Style.STROKE);
            borda.setStrokeWidth(2.5f * d);
            setClickable(false);
            setFocusable(false);
        }

        void mover(int tecla, int repeticoes) {
            if (x < 0) { x = getWidth() / 2f; y = getHeight() / 2f; }
            float d = getResources().getDisplayMetrics().density;
            float passo = 10 * d * (1 + Math.min(repeticoes, 15) * 0.35f);
            float dx = 0, dy = 0;
            if (tecla == KeyEvent.KEYCODE_DPAD_LEFT) dx = -passo;
            if (tecla == KeyEvent.KEYCODE_DPAD_RIGHT) dx = passo;
            if (tecla == KeyEvent.KEYCODE_DPAD_UP) dy = -passo;
            if (tecla == KeyEvent.KEYCODE_DPAD_DOWN) dy = passo;

            float nx = x + dx, ny = y + dy;
            float margem = raio;
            float rolarX = 0, rolarY = 0;
            if (nx < margem) { rolarX = nx - margem; nx = margem; }
            if (nx > getWidth() - margem) { rolarX = nx - (getWidth() - margem); nx = getWidth() - margem; }
            if (ny < margem) { rolarY = ny - margem; ny = margem; }
            if (ny > getHeight() - margem) { rolarY = ny - (getHeight() - margem); ny = getHeight() - margem; }
            x = nx;
            y = ny;
            // Encostou na borda: rola a pagina
            if (rolarX != 0 || rolarY != 0) rolarPagina(x, y, rolarX * 4, rolarY * 4);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (x < 0) { x = getWidth() / 2f; y = getHeight() / 2f; }
            canvas.drawCircle(x, y, raio, preenchimento);
            canvas.drawCircle(x, y, raio, borda);
        }
    }

    // ---------- Ciclo de vida ----------

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (telaCheia != null) { chrome.onHideCustomView(); return; }
        if (web.canGoBack()) { web.goBack(); return; }
        super.onBackPressed();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int pedido, int resultado, Intent dados) {
        if (pedido == PEDIDO_ARQUIVO && escolhaArquivo != null) {
            escolhaArquivo.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultado, dados));
            escolhaArquivo = null;
            return;
        }
        super.onActivityResult(pedido, resultado, dados);
    }

    @Override
    protected void onSaveInstanceState(Bundle saida) {
        super.onSaveInstanceState(saida);
        web.saveState(saida);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        esconderBarras();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            raiz.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
