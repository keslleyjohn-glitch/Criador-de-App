package app.webview;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;

import java.util.HashMap;
import java.util.Map;

/**
 * Player proprio do Android: toca o video do site usando a placa de video
 * do aparelho (bem mais leve que tocar dentro da pagina).
 * Voltar = fecha o player e volta para o site.
 */
public class PlayerActivity extends Activity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_CABECALHOS = "cabecalhos";
    public static final String EXTRA_AGENTE = "agente";
    public static final int RESULTADO_ERRO = RESULT_FIRST_USER;

    private ExoPlayer player;
    private PlayerView tela;
    private String url;
    private boolean comecouATocar = false;

    @SuppressWarnings("unchecked")
    @Override
    protected void onCreate(Bundle estadoSalvo) {
        super.onCreate(estadoSalvo);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        url = getIntent().getStringExtra(EXTRA_URL);
        String agente = getIntent().getStringExtra(EXTRA_AGENTE);
        Map<String, String> cabecalhos = null;
        try {
            cabecalhos = (HashMap<String, String>) getIntent().getSerializableExtra(EXTRA_CABECALHOS);
        } catch (Exception ignorado) { }
        if (cabecalhos == null) cabecalhos = new HashMap<>();

        Intent resposta = new Intent();
        resposta.putExtra(EXTRA_URL, url);
        setResult(RESULT_OK, resposta);

        if (url == null) {
            finish();
            return;
        }

        tela = new PlayerView(this);
        tela.setBackgroundColor(Color.BLACK);
        tela.setKeepScreenOn(true);
        tela.setControllerShowTimeoutMs(3500);
        setContentView(tela);

        DefaultHttpDataSource.Factory internet = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setDefaultRequestProperties(cabecalhos);
        if (agente != null && !agente.isEmpty()) internet.setUserAgent(agente);

        DefaultMediaSourceFactory fontes = new DefaultMediaSourceFactory(this)
                .setDataSourceFactory(internet);

        // Aparelho fraco: no maximo 720p, e se um decodificador falhar tenta outro
        DefaultTrackSelector seletor = new DefaultTrackSelector(this);
        seletor.setParameters(seletor.buildUponParameters().setMaxVideoSize(1280, 720).build());
        DefaultRenderersFactory decodificadores = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true);

        player = new ExoPlayer.Builder(this, decodificadores)
                .setMediaSourceFactory(fontes)
                .setTrackSelector(seletor)
                .build();

        MediaItem.Builder item = new MediaItem.Builder().setUri(url);
        String u = url.toLowerCase();
        if (u.contains(".m3u8") || u.contains("m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (u.contains(".mpd")) item.setMimeType(MimeTypes.APPLICATION_MPD);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int estado) {
                if (estado == Player.STATE_READY) comecouATocar = true;
            }

            @Override
            public void onPlayerError(PlaybackException erro) {
                // Ao vivo "atrasou demais": pula para o momento atual
                if (erro.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                // Caiu depois de ja estar tocando: tenta de novo uma vez
                if (comecouATocar) {
                    comecouATocar = false;
                    player.prepare();
                    return;
                }
                Intent falhou = new Intent();
                falhou.putExtra(EXTRA_URL, url);
                setResult(RESULTADO_ERRO, falhou);
                finish();
            }
        });

        tela.setPlayer(player);
        player.setMediaItem(item.build());
        player.setPlayWhenReady(true);
        player.prepare();
        tela.requestFocus();
        esconderBarras();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Tecla Esc (o "voltar" de alguns teclados) fecha o player
        if (e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE) {
            if (e.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
        return super.dispatchKeyEvent(e);
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

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null) player.play();
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
