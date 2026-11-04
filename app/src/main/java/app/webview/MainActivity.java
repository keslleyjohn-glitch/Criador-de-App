package app.webview;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebRequestError;
import org.mozilla.geckoview.WebResponse;

/**
 * Aplicativo de uma tela so: abre o site configurado em tela cheia,
 * sem barra de endereco. Usa o motor do Firefox (GeckoView), que vem
 * dentro do app, entao funciona ate em aparelhos com sistema antigo.
 * Celular: toque normal. TV box: cursor nas setas, OK clica.
 */
public class MainActivity extends Activity {

    private static GeckoRuntime motor; // so pode existir um por app

    private FrameLayout raiz;
    private GeckoView tela;
    private GeckoSession sessao;
    private Cursor cursor;
    private String site;
    private boolean modoTv;
    private boolean podeVoltar = false;
    private boolean telaCheia = false;

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

        tela = new GeckoView(this);
        raiz.addView(tela, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cursor = new Cursor(this);
        cursor.setVisibility(modoTv ? View.VISIBLE : View.INVISIBLE);
        raiz.addView(cursor, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(raiz);

        if (motor == null) {
            GeckoRuntimeSettings ajustes = new GeckoRuntimeSettings.Builder()
                    .consoleOutput(false)
                    .build();
            motor = GeckoRuntime.create(getApplicationContext(), ajustes);
        }

        sessao = new GeckoSession();
        sessao.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onFullScreen(GeckoSession s, boolean cheia) {
                telaCheia = cheia;
                if (cheia) {
                    cursor.setVisibility(View.INVISIBLE);
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
                esconderBarras();
            }

            @Override
            public void onExternalResponse(GeckoSession s, WebResponse resposta) {
                // Downloads: entrega para o navegador do aparelho
                abrirFora(Uri.parse(resposta.uri));
            }
        });
        sessao.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onCanGoBack(GeckoSession s, boolean pode) {
                podeVoltar = pode;
            }

            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest pedido) {
                if (tratarLink(pedido.uri)) return GeckoResult.fromValue(AllowOrDeny.DENY);
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                // Links "abrir em nova aba" abrem aqui mesmo
                if (!tratarLink(uri)) sessao.loadUri(uri);
                return null;
            }

            @Override
            public GeckoResult<String> onLoadError(GeckoSession s, String uri, WebRequestError erro) {
                return GeckoResult.fromValue("resource://android/assets/erro.html");
            }
        });

        sessao.open(motor);
        tela.setSession(sessao);
        sessao.loadUri(site);

        tela.setFocusable(true);
        tela.setFocusableInTouchMode(true);
        tela.requestFocus();

        esconderBarras();
        if (modoTv) handler.postDelayed(esconderCursor, 6000);
    }

    private boolean detectarTv() {
        UiModeManager um = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        if (um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        return !getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    /** Links normais abrem no app; WhatsApp, telefone, mapas etc. abrem no app certo. */
    private boolean tratarLink(String url) {
        if (url == null) return false;
        Uri u = Uri.parse(url);
        String esquema = u.getScheme();
        if (esquema == null) return false;
        esquema = esquema.toLowerCase();
        if (esquema.equals("http") || esquema.equals("https") || esquema.equals("about")
                || esquema.equals("data") || esquema.equals("blob") || esquema.equals("javascript")
                || esquema.equals("resource") || esquema.equals("moz-extension")) {
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
                } catch (Exception e) {
                    String alternativo = i.getStringExtra("browser_fallback_url");
                    if (alternativo != null) sessao.loadUri(alternativo);
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
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignorado) { }
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
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) voltar();
            return true;
        }
        if (!telaCheia) {
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

    private void toque(int acao, long inicio, long agora, float x, float y) {
        MotionEvent ev = MotionEvent.obtain(inicio, agora, acao, x, y, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        tela.dispatchTouchEvent(ev);
        ev.recycle();
    }

    private void clicar(float x, float y) {
        long t = SystemClock.uptimeMillis();
        toque(MotionEvent.ACTION_DOWN, t, t, x, y);
        toque(MotionEvent.ACTION_UP, t, t + 60, x, y);
    }

    /** Rola a parte do site embaixo do cursor "arrastando o dedo" nela. */
    private void rolarPagina(float x, float y, float dx, float dy) {
        long t = SystemClock.uptimeMillis();
        int passos = 6;
        toque(MotionEvent.ACTION_DOWN, t, t, x, y);
        for (int i = 1; i <= passos; i++) {
            float f = i / (float) passos;
            toque(MotionEvent.ACTION_MOVE, t, t + i * 30L, x - dx * f, y - dy * f);
        }
        toque(MotionEvent.ACTION_UP, t, t + passos * 30L + 120L, x - dx, y - dy);
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
            boolean naBorda = false;
            if (nx < margem) { nx = margem; naBorda = true; }
            if (nx > getWidth() - margem) { nx = getWidth() - margem; naBorda = true; }
            if (ny < margem) { ny = margem; naBorda = true; }
            if (ny > getHeight() - margem) { ny = getHeight() - margem; naBorda = true; }
            x = nx;
            y = ny;
            invalidate();

            // Encostou na borda: rola a pagina naquela direcao.
            // O "arrasto" comeca um pouco para dentro da tela.
            if (naBorda) {
                float quanto = 120 * d * (1 + Math.min(repeticoes, 10) * 0.15f);
                float ix = Math.max(getWidth() * 0.15f, Math.min(getWidth() * 0.85f, x));
                float iy = Math.max(getHeight() * 0.25f, Math.min(getHeight() * 0.75f, y));
                rolarPagina(ix, iy, Math.signum(dx) * quanto, Math.signum(dy) * quanto);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (x < 0) { x = getWidth() / 2f; y = getHeight() / 2f; }
            canvas.drawCircle(x, y, raio, preenchimento);
            canvas.drawCircle(x, y, raio, borda);
        }
    }

    // ---------- Ciclo de vida ----------

    private void voltar() {
        if (telaCheia) { sessao.exitFullScreen(); return; }
        if (podeVoltar) { sessao.goBack(); return; }
        finish();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        voltar();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sessao != null) sessao.setActive(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sessao != null) sessao.setActive(true);
        esconderBarras();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (sessao != null) sessao.close();
        super.onDestroy();
    }
}
