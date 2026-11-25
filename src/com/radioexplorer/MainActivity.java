package com.radioexplorer;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.IBinder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(19, 17, 20);
    private static final int PANEL = Color.rgb(36, 29, 34);
    private static final int ACCENT = Color.rgb(226, 93, 120);
    private final Catalog catalog = new Catalog();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newFixedThreadPool(2);
    private final List<Catalog.Station> shown = new ArrayList<>();
    private final Map<String, Catalog.Station> favorites = new LinkedHashMap<>();
    private final StationAdapter adapter = new StationAdapter();
    private SharedPreferences prefs;
    private String country, query, tag;
    private boolean onlyFavorites, loading, hasMore, loadError, destroyed;
    private int generation, offset;
    private EditText search;
    private TextView status;
    private Button countryButton, favoriteButton, genreButton;
    private ListView list;
    private TextView listFooter;
    private LinearLayout playerBar;
    private TextView playerTitle;
    private ImageButton playerToggle;
    private PlaybackService playback;
    private boolean bound;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            playback = ((PlaybackService.LocalBinder) binder).service();
            playback.observe(() -> updatePlayer());
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            playback = null;
            updatePlayer();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        prefs = getSharedPreferences("radio", MODE_PRIVATE);
        country = prefs.getString("country", "ES");
        query = state == null ? "" : state.getString("query", "");
        tag = state == null ? "" : state.getString("tag", "");
        onlyFavorites = state != null && state.getBoolean("favorites");
        try {
            JSONArray saved = new JSONArray(prefs.getString("favorites", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                Catalog.Station s = new Catalog.Station(saved.getJSONObject(i));
                if (Catalog.playable(s.url)) favorites.put(s.id, s);
            }
        } catch (JSONException e) {
            Log.e("RadioExplorer", "Cannot read favorites", e);
            new AlertDialog.Builder(this).setMessage("No se pudieron leer los favoritos guardados.")
                    .setPositiveButton("Aceptar", null).show();
        }
        buildUi();
        refresh();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private Button button(String value, View.OnClickListener action) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setOnClickListener(action);
        return b;
    }

    private ImageButton iconButton(int icon, String label, View.OnClickListener action) {
        ImageButton button = new ImageButton(this);
        TypedValue background = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true);
        button.setBackgroundResource(background.resourceId);
        button.setImageResource(icon);
        button.setContentDescription(label);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setOnClickListener(action);
        return button;
    }

    private void updateFavoriteFilter() {
        favoriteButton.setText(onlyFavorites ? "Ver todas" : "Favoritos");
        favoriteButton.setCompoundDrawablesWithIntrinsicBounds(
                onlyFavorites ? R.drawable.ic_favorite_on : R.drawable.ic_favorite_off, 0, 0, 0);
        favoriteButton.setCompoundDrawablePadding(dp(6));
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(12), dp(12), dp(12), 0);
        setContentView(root);
        LinearLayout title = new LinearLayout(this);
        title.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text("Radio Explorer", 27, ACCENT);
        name.setTypeface(null, Typeface.BOLD);
        title.addView(name, new LinearLayout.LayoutParams(0, dp(52), 1));
        ImageButton info = iconButton(R.drawable.ic_info, "Información", v -> about());
        title.addView(info, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(title);

        LinearLayout searchRow = new LinearLayout(this);
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(Color.LTGRAY);
        search.setTextSize(16);
        search.setHint("Buscar emisora");
        search.setContentDescription("Buscar emisora por nombre");
        search.setText(query);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        search.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH) { submitSearch(); return true; }
            return false;
        });
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(54), 1));
        searchRow.addView(button("Buscar", v -> submitSearch()));
        root.addView(searchRow);

        LinearLayout filters = new LinearLayout(this);
        countryButton = button(countryName(country), v -> chooseCountry());
        favoriteButton = button("", v -> {
            onlyFavorites = !onlyFavorites;
            updateFavoriteFilter();
            refresh();
        });
        updateFavoriteFilter();
        filters.addView(countryButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        filters.addView(favoriteButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(filters);
        LinearLayout actions = new LinearLayout(this);
        genreButton = button(tag.isEmpty() ? "Todos los géneros" : tag, v -> chooseGenre());
        actions.addView(genreButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        actions.addView(button("Actualizar", v -> refresh()), new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(actions);

        status = text("", 13, Color.LTGRAY);
        status.setPadding(dp(4), dp(8), dp(4), dp(8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);
        list = new ListView(this);
        list.setDividerHeight(dp(6));
        listFooter = text("", 14, Color.LTGRAY);
        listFooter.setGravity(Gravity.CENTER);
        listFooter.setPadding(dp(8), dp(12), dp(8), dp(12));
        listFooter.setOnClickListener(v -> { if (loadError) loadPage(); });
        listFooter.setVisibility(View.GONE);
        list.addFooterView(listFooter, null, false);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < shown.size()) play(shown.get(position));
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) { }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                if (visible > 0 && first + visible >= shown.size() - 6) maybeLoadMore();
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        playerBar = new LinearLayout(this);
        playerBar.setGravity(Gravity.CENTER_VERTICAL);
        playerBar.setBackgroundColor(PANEL);
        playerTitle = text("", 14, Color.WHITE);
        playerTitle.setPadding(dp(8), dp(6), dp(4), dp(6));
        playerTitle.setMaxLines(2);
        playerBar.addView(playerTitle, new LinearLayout.LayoutParams(0, -2, 1));
        playerToggle = iconButton(R.drawable.ic_pause, "Pausar", v -> {
            if (playback == null) return;
            if (playback.playing()) playback.pause(true); else playback.resume();
        });
        playerBar.addView(playerToggle, new LinearLayout.LayoutParams(dp(48), dp(48)));
        playerBar.addView(iconButton(R.drawable.ic_stop, "Detener", v -> {
            if (playback != null) playback.stopPlayback();
        }), new LinearLayout.LayoutParams(dp(48), dp(48)));
        playerBar.setVisibility(View.GONE);
        root.addView(playerBar, new LinearLayout.LayoutParams(-1, dp(64)));
    }

    private void updatePlayer() {
        if (playerBar == null) return;
        if (playback == null || !playback.hasStation()) {
            playerBar.setVisibility(View.GONE);
            return;
        }
        playerBar.setVisibility(View.VISIBLE);
        playerTitle.setText(playback.title() + "\n" + playback.status());
        playerToggle.setImageResource(playback.playing() ? R.drawable.ic_pause : R.drawable.ic_play);
        playerToggle.setContentDescription(playback.playing() ? "Pausar" : "Reproducir");
    }

    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, PlaybackService.class), connection, BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        if (bound) {
            if (playback != null) playback.observe(null);
            unbindService(connection);
            bound = false;
            playback = null;
        }
        super.onStop();
    }

    private void submitSearch() {
        query = search.getText().toString().trim();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
        refresh();
    }

    private void refresh() {
        generation++;
        loading = false;
        offset = 0;
        shown.clear();
        hasMore = false;
        loadError = false;
        listFooter.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        if (onlyFavorites) {
            String q = query.toLowerCase(Locale.ROOT);
            for (Catalog.Station s : favorites.values()) {
                if (s.name.toLowerCase(Locale.ROOT).contains(q)
                        && (country.isEmpty() || s.country.equals(country))
                        && (tag.isEmpty() || s.tags.toLowerCase(Locale.ROOT).contains(tag.toLowerCase(Locale.ROOT)))) {
                    shown.add(s);
                }
            }
            adapter.notifyDataSetChanged();
            status.setText(shown.size() + " favoritos · filtros de país y género activos");
        } else {
            loadPage();
        }
    }

    private void maybeLoadMore() {
        if (!onlyFavorites && !loading && hasMore && !loadError) loadPage();
    }

    private void loadPage() {
        if (loading || onlyFavorites) return;
        loading = true;
        loadError = false;
        final int token = generation, start = offset;
        final String c = country, q = query, t = tag;
        if (shown.isEmpty()) status.setText("Consultando Radio Browser…");
        listFooter.setText("Cargando emisoras…");
        listFooter.setContentDescription(null);
        listFooter.setEnabled(false);
        listFooter.setVisibility(View.VISIBLE);
        network.execute(() -> {
            try {
                Catalog.Page page = catalog.search(c, q, t, start);
                ui.post(() -> {
                    if (destroyed || generation != token) return;
                    Set<String> ids = new HashSet<>();
                    for (Catalog.Station s : shown) ids.add(s.id);
                    for (Catalog.Station s : page.stations) if (ids.add(s.id)) shown.add(s);
                    offset += page.rawCount;
                    hasMore = page.rawCount == Catalog.PAGE_SIZE;
                    loading = false;
                    adapter.notifyDataSetChanged();
                    status.setText(shown.isEmpty() ? "Sin resultados. Prueba otro país, género o nombre."
                            : shown.size() + " emisoras cargadas · operativas según Radio Browser");
                    if (hasMore) {
                        listFooter.setVisibility(View.GONE);
                        list.post(() -> {
                            if (generation == token && (shown.isEmpty()
                                    || (list.getChildCount() > 0
                                    && list.getLastVisiblePosition() >= shown.size() - 6))) maybeLoadMore();
                        });
                    } else if (shown.isEmpty()) {
                        listFooter.setVisibility(View.GONE);
                    } else {
                        listFooter.setText("No hay más emisoras");
                        listFooter.setEnabled(false);
                        listFooter.setVisibility(View.VISIBLE);
                    }
                });
            } catch (IOException | JSONException e) {
                Log.e("RadioExplorer", "Station search failed", e);
                ui.post(() -> {
                    if (destroyed || generation != token) return;
                    loading = false;
                    loadError = true;
                    status.setText("No se pudo cargar la lista. Revisa tu conexión y pulsa Reintentar.");
                    listFooter.setText("Reintentar");
                    listFooter.setContentDescription("Reintentar la carga de emisoras");
                    listFooter.setEnabled(true);
                    listFooter.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private static String countryName(String code) {
        return code.isEmpty() ? "Todo el mundo" : new Locale("", code).getDisplayCountry(new Locale("es"));
    }

    private void chooseCountry() {
        final int token = generation;
        countryButton.setEnabled(false);
        network.execute(() -> {
            try {
                JSONArray data = catalog.countries();
                List<String> codes = new ArrayList<>();
                for (int i = 0; i < data.length(); i++) {
                    String code = data.getJSONObject(i).getString("name").toUpperCase(Locale.ROOT);
                    if (code.matches("[A-Z]{2}") && !codes.contains(code)) codes.add(code);
                }
                Collator col = Collator.getInstance(new Locale("es"));
                codes.sort((a, b) -> col.compare(countryName(a), countryName(b)));
                codes.add(0, "");
                String[] names = new String[codes.size()];
                for (int i = 0; i < codes.size(); i++) names[i] = countryName(codes.get(i));
                ui.post(() -> {
                    if (destroyed) return;
                    countryButton.setEnabled(true);
                    if (token != generation) return;
                    new AlertDialog.Builder(this).setTitle("País")
                            .setItems(names, (dialog, index) -> {
                                country = codes.get(index);
                                prefs.edit().putString("country", country).apply();
                                countryButton.setText(countryName(country));
                                refresh();
                            }).setNegativeButton("Cancelar", null).show();
                });
            } catch (IOException | JSONException e) {
                Log.e("RadioExplorer", "Country list failed", e);
                ui.post(() -> {
                    if (destroyed) return;
                    countryButton.setEnabled(true);
                    new AlertDialog.Builder(this).setMessage("No se pudieron cargar los países. Revisa tu conexión.")
                            .setPositiveButton("Aceptar", null).show();
                });
            }
        });
    }

    private void chooseGenre() {
        String[] names = {"Todos los géneros", "Pop", "Rock", "Jazz", "Clásica", "Noticias", "Deportes", "Electrónica", "Chillout"};
        String[] tags = {"", "pop", "rock", "jazz", "classical", "news", "sports", "electronic", "chillout"};
        new AlertDialog.Builder(this).setTitle("Género").setItems(names, (dialog, which) -> {
            tag = tags[which];
            genreButton.setText(names[which]);
            refresh();
        }).setNegativeButton("Cancelar", null).show();
    }

    private void toggleFavorite(Catalog.Station s) {
        if (favorites.containsKey(s.id)) favorites.remove(s.id);
        else favorites.put(s.id, s);
        JSONArray saved = new JSONArray();
        for (Catalog.Station f : favorites.values()) saved.put(f.json);
        prefs.edit().putString("favorites", saved.toString()).apply();
        if (onlyFavorites) refresh();
        else adapter.notifyDataSetChanged();
    }

    private void play(Catalog.Station s) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        Intent intent = new Intent(this, PlaybackService.class).setAction(PlaybackService.PLAY)
                .putExtra(PlaybackService.EXTRA_NAME, s.name).putExtra(PlaybackService.EXTRA_URL, s.url);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        network.execute(() -> {
            try {
                catalog.click(s.id);
            } catch (IOException | JSONException e) {
                Log.w("RadioExplorer", "Radio Browser listening counter unavailable", e);
            }
        });
    }

    private void about() {
        new AlertDialog.Builder(this).setTitle("Radio Explorer")
                .setMessage("Catálogo público de Radio Browser (radio-browser.info).\n\n"
                        + "Se ocultan las emisoras que su última comprobación marca como caídas. "
                        + "No es una garantía de disponibilidad: una emisión puede fallar después o estar bloqueada en tu región.\n\n"
                        + "LibVLC reproduce el audio dentro de la app, también con la pantalla apagada. Los favoritos se guardan en este móvil.\n\n"
                        + "La app consulta Radio Browser y notifica a su API qué emisora se abre para su contador público de escuchas. "
                        + "El proveedor y las emisoras reciben tu IP al conectar. Esta app no añade analítica ni anuncios.\n\n"
                        + "No alojamos emisiones ni somos responsables del contenido de terceros. "
                        + "El usuario debe respetar la legislación aplicable. App MIT; LibVLC LGPL 2.1 o posterior. Sin garantía. Creado con ayuda de IA.")
                .setPositiveButton("Aceptar", null)
                .setNeutralButton("Licencia LibVLC", (dialog, which) -> showLibraryLicense())
                .show();
    }

    private void showLibraryLicense() {
        try (InputStream input = getAssets().open("licenses/LGPL-2.1.txt");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            new AlertDialog.Builder(this).setTitle("LibVLC — LGPL 2.1")
                    .setMessage(output.toString("UTF-8")).setPositiveButton("Cerrar", null).show();
        } catch (IOException e) {
            Log.e("RadioExplorer", "Cannot read LibVLC license", e);
            new AlertDialog.Builder(this).setMessage("No se pudo abrir la licencia de LibVLC.")
                    .setPositiveButton("Aceptar", null).show();
        }
    }

    private final class StationAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Catalog.Station getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Catalog.Station s = getItem(position);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(4), dp(10));
            row.setMinimumHeight(dp(88));
            row.setBackgroundColor(PANEL);
            LinearLayout labels = new LinearLayout(MainActivity.this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView title = text(s.name, 18, Color.WHITE);
            title.setTypeface(null, Typeface.BOLD);
            title.setMaxLines(2);
            labels.addView(title);
            String details = countryName(s.country) + " · " + s.codec;
            if (s.bitrate > 0) details += " · " + s.bitrate + " kbps";
            TextView subtitle = text(details, 12, ACCENT);
            labels.addView(subtitle);
            if (!s.tags.isEmpty()) {
                TextView tags = text(s.tags.replace(",", " · "), 12, Color.LTGRAY);
                tags.setMaxLines(1);
                tags.setEllipsize(android.text.TextUtils.TruncateAt.END);
                labels.addView(tags);
            }
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            boolean favorite = favorites.containsKey(s.id);
            ImageButton heart = iconButton(favorite ? R.drawable.ic_favorite_on : R.drawable.ic_favorite_off,
                    (favorite ? "Quitar de favoritos: " : "Añadir a favoritos: ") + s.name,
                    v -> toggleFavorite(s));
            heart.setFocusable(false);
            row.addView(heart, new LinearLayout.LayoutParams(dp(48), dp(48)));
            return row;
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("query", query);
        state.putString("tag", tag);
        state.putBoolean("favorites", onlyFavorites);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        network.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
