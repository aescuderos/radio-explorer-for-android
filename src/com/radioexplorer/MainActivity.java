package com.radioexplorer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
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
    private boolean onlyFavorites, loading, hasMore, destroyed;
    private int generation, offset;
    private EditText search;
    private TextView status;
    private Button countryButton, favoriteButton, genreButton, more;

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
        Button info = button("Info", v -> about());
        title.addView(info);
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
        favoriteButton = button(onlyFavorites ? "★ Favoritos" : "☆ Favoritos", v -> {
            onlyFavorites = !onlyFavorites;
            favoriteButton.setText(onlyFavorites ? "★ Favoritos" : "☆ Favoritos");
            refresh();
        });
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
        ListView list = new ListView(this);
        list.setDividerHeight(dp(6));
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> play(shown.get(position)));
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        more = button("Cargar más", v -> loadPage());
        root.addView(more, new LinearLayout.LayoutParams(-1, dp(52)));
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
        adapter.notifyDataSetChanged();
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
            more.setVisibility(View.GONE);
        } else {
            loadPage();
        }
    }

    private void loadPage() {
        if (loading || onlyFavorites) return;
        loading = true;
        final int token = generation, start = offset;
        final String c = country, q = query, t = tag;
        status.setText("Consultando Radio Browser…");
        more.setVisibility(View.VISIBLE);
        more.setEnabled(false);
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
                            : shown.size() + " emisoras · operativas según Radio Browser");
                    more.setText("Cargar más");
                    more.setEnabled(true);
                    more.setVisibility(hasMore ? View.VISIBLE : View.GONE);
                });
            } catch (IOException | JSONException e) {
                Log.e("RadioExplorer", "Station search failed", e);
                ui.post(() -> {
                    if (destroyed || generation != token) return;
                    loading = false;
                    status.setText("No se pudo cargar la lista. Revisa tu conexión y pulsa Reintentar.");
                    more.setVisibility(View.VISIBLE);
                    more.setText("Reintentar");
                    more.setEnabled(true);
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
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(s.url), "audio/*");
        intent.putExtra("title", s.name);
        intent.setPackage("org.videolan.vlc");
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            intent.setPackage(null);
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException noPlayer) {
                new AlertDialog.Builder(this).setMessage("Instala VLC u otro reproductor de audio para escuchar emisoras.")
                        .setPositiveButton("Aceptar", null).show();
                return;
            }
        }
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
                        + "VLC reproduce el audio, también con la pantalla apagada. Los favoritos se guardan en este móvil.\n\n"
                        + "La app consulta Radio Browser y notifica a su API qué emisora se abre para su contador público de escuchas. "
                        + "El proveedor y las emisoras reciben tu IP al conectar. Esta app no añade analítica ni anuncios.\n\n"
                        + "No alojamos emisiones ni somos responsables del contenido de terceros. "
                        + "El usuario debe respetar la legislación aplicable. Software MIT, sin garantía. Creado con ayuda de IA.")
                .setPositiveButton("Aceptar", null).show();
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
            Button star = button(favorites.containsKey(s.id) ? "★" : "☆", v -> toggleFavorite(s));
            star.setFocusable(false);
            star.setTextSize(25);
            star.setTextColor(ACCENT);
            star.setContentDescription((favorites.containsKey(s.id) ? "Quitar de favoritos: " : "Añadir a favoritos: ") + s.name);
            row.addView(star, new LinearLayout.LayoutParams(dp(56), dp(56)));
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
