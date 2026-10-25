package io.github.borey.recorderautotranscribe;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class SettingsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<Bundle> all = new ArrayList<>(), visible = new ArrayList<>();

    // ColorOS Aquamorphic / Dark Luxury Design Palette
    private static final int COLOR_BG = Color.parseColor("#111318");
    private static final int COLOR_CARD = Color.parseColor("#1A1D24");
    private static final int COLOR_CARD_STROKE = Color.parseColor("#2B303C");
    private static final int COLOR_DIVIDER = Color.parseColor("#252A35");
    private static final int COLOR_ACCENT = Color.parseColor("#1F70F5");
    private static final int COLOR_ACCENT_PRESSED = Color.parseColor("#1659C7");
    private static final int COLOR_PRIMARY_TEXT = Color.parseColor("#FFFFFF");
    private static final int COLOR_SECONDARY_TEXT = Color.parseColor("#8E95A5");
    private static final int COLOR_PILL_NEUTRAL = Color.parseColor("#222630");

    private static final int COLOR_SUCCESS_TEXT = Color.parseColor("#23C16B");
    private static final int COLOR_SUCCESS_BG = Color.parseColor("#1A3326");
    private static final int COLOR_WARNING_TEXT = Color.parseColor("#F5A623");
    private static final int COLOR_WARNING_BG = Color.parseColor("#332A1A");
    private static final int COLOR_ERROR_TEXT = Color.parseColor("#FA5A5A");
    private static final int COLOR_ERROR_BG = Color.parseColor("#381E22");

    private TextView statusDot, statusSub;
    private TextView metricTotal, metricTranscribed, metricSummarized, metricUnfinished, metricFooter, metricBadge;
    private TextView filterSummary, emptyState;
    private Button scan, clearFilters;
    private Switch enabled, summary, conditions;
    private EditText search;
    private TextView[] filterSegments;
    private int filterMode = 0; // 0: 全部, 1: 处理中, 2: 未完成, 3: 全部完成
    private LinearLayout groups;
    private static final String ALL_GROUP = "__all_recordings__";
    private String selectedGroup = ALL_GROUP;
    private BaseAdapter adapter;
    private ListView list;
    private HorizontalScrollView groupScrollView;
    private LinearLayout groupRow;
    private final LinkedHashMap<String, Integer> currentGroupCounts = new LinkedHashMap<>();
    private String lastRenderedSelectedGroup = null;
    private final Runnable filterRunnable = this::applyFilter;
    private long updated, requested, generation, statusEpoch;
    private boolean resumed, loading, snapshotLoaded, snapshotStale, applyingOnboarding;
    private final Runnable refresh = () -> loadStatus();

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable cardDrawable(int bgColor, int strokeColor, int strokeWidthDp, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(bgColor);
        if (strokeWidthDp > 0 && strokeColor != 0) d.setStroke(dp(strokeWidthDp), strokeColor);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private RippleDrawable rippleDrawable(int normalColor, int pressedColor, int strokeColor, int radiusDp) {
        GradientDrawable content = cardDrawable(normalColor, strokeColor, strokeColor != 0 ? 1 : 0, radiusDp);
        GradientDrawable mask = cardDrawable(Color.WHITE, 0, 0, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(pressedColor), content, mask);
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(COLOR_DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(0, dp(4), 0, dp(4));
        v.setLayoutParams(lp);
        return v;
    }

    private TextView sectionHeader(String title) {
        TextView v = new TextView(this);
        v.setText(title);
        v.setTextSize(15);
        v.setTextColor(COLOR_PRIMARY_TEXT);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(14), 0, dp(8));
        return v;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            try {
                WindowInsetsController controller = decor.getWindowInsetsController();
                if (controller != null) {
                    controller.setSystemBarsAppearance(0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS |
                            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
            } catch (Throwable ignored) {
            }
        } else {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setNavigationBarContrastEnforced(false);
        }

        list = new ListView(this);
        list.setBackgroundColor(COLOR_BG);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(10));
        list.setItemsCanFocus(true);
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        list.setClipToPadding(false);
        list.setPadding(dp(16), 0, dp(16), dp(16));
        list.setOnApplyWindowInsetsListener((v, insets) -> {
            int topInset, bottomInset, leftInset, rightInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                topInset = bars.top;
                bottomInset = bars.bottom;
                leftInset = bars.left;
                rightInset = bars.right;
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
                leftInset = insets.getSystemWindowInsetLeft();
                rightInset = insets.getSystemWindowInsetRight();
            }
            v.setPadding(dp(16) + leftInset, topInset + dp(8), dp(16) + rightInset, dp(16) + bottomInset);
            return insets;
        });

        LinearLayout header = column();
        header.setPadding(0, dp(16), 0, dp(10));

        // 1. Hero Title & Subtitle
        TextView titleView = new TextView(this);
        titleView.setText("录音自动转写");
        titleView.setTextSize(24);
        titleView.setTextColor(COLOR_PRIMARY_TEXT);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(titleView);

        String recorder;
        try { recorder = "录音机 v" + getPackageManager().getPackageInfo(MainHook.TARGET, 0).versionName; }
        catch (Exception e) { recorder = "未检测到录音机"; }
        TextView subTitleView = new TextView(this);
        subTitleView.setText("ColorOS 录音后台自动化  ·  模块 v" + BuildConfig.VERSION_NAME + "  ·  " + recorder);
        subTitleView.setTextSize(13);
        subTitleView.setTextColor(COLOR_SECONDARY_TEXT);
        subTitleView.setPadding(0, dp(4), 0, dp(14));
        header.addView(subTitleView);

        // 2. Status & Inspection Card
        LinearLayout statusCard = column();
        statusCard.setBackground(cardDrawable(COLOR_CARD, COLOR_CARD_STROKE, 1, 16));
        statusCard.setPadding(dp(16), dp(14), dp(16), dp(14));

        statusDot = new TextView(this);
        statusDot.setText("● 正在读取模块状态…");
        statusDot.setTextSize(14);
        statusDot.setTextColor(COLOR_WARNING_TEXT);
        statusDot.setTypeface(Typeface.DEFAULT_BOLD);
        statusCard.addView(statusDot);

        statusSub = new TextView(this);
        statusSub.setText("正在连接后台服务…");
        statusSub.setTextSize(12);
        statusSub.setTextColor(COLOR_SECONDARY_TEXT);
        statusSub.setPadding(0, dp(6), 0, dp(12));
        statusCard.addView(statusSub);

        scan = new Button(this);
        scan.setText("⟳ 立即检查与处理录音");
        scan.setContentDescription("检查新增和待处理录音并刷新状态");
        scan.setTextColor(Color.WHITE);
        scan.setTextSize(14);
        scan.setTypeface(Typeface.DEFAULT_BOLD);
        scan.setMinHeight(dp(44));
        scan.setBackground(rippleDrawable(COLOR_ACCENT, COLOR_ACCENT_PRESSED, 0, 12));
        scan.setOnClickListener(v -> request("scan", 0));
        statusCard.addView(scan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        header.addView(statusCard);

        // 3. Grouped Settings Card
        header.addView(sectionHeader("核心设置"));
        LinearLayout settingsCard = column();
        settingsCard.setBackground(cardDrawable(COLOR_CARD, COLOR_CARD_STROKE, 1, 16));
        settingsCard.setPadding(dp(16), dp(6), dp(16), dp(6));

        ModuleSettings s = ModuleSettings.load(this);
        enabled = new Switch(this); enabled.setChecked(s.enabled);
        summary = new Switch(this); summary.setChecked(s.aiSummaryEnabled);
        conditions = new Switch(this); conditions.setChecked(s.respectRecorderConditions);
        enabled.setOnCheckedChangeListener((b, v) -> save());
        summary.setOnCheckedChangeListener((b, v) -> save());
        conditions.setOnCheckedChangeListener((b, v) -> save());

        settingsCard.addView(settingRow("自动处理录音", "后台自动检查并排队转写新录音", enabled));
        settingsCard.addView(divider());
        settingsCard.addView(settingRow("完成转写后生成 AI 摘要", "满足文本长度时自动触发原应用 AI 摘要", summary));
        settingsCard.addView(divider());
        settingsCard.addView(settingRow("仅满足原应用条件时处理", "遵循 Wi-Fi、充电与系统空闲限制；关闭则有网即处理", conditions));
        header.addView(settingsCard);

        TextView scopeHint = new TextView(this);
        scopeHint.setText("ℹ 需要 Root + LSPosed；作用域勾选录音机和 AI 语音摘记／无障碍助手。");
        scopeHint.setTextSize(11);
        scopeHint.setTextColor(COLOR_SECONDARY_TEXT);
        scopeHint.setPadding(dp(4), dp(6), dp(4), dp(6));
        header.addView(scopeHint);

        // 4. Statistics Dashboard (4-Grid Metrics Dashboard)
        header.addView(buildMetricsCard());

        // 5. Recording Groups
        header.addView(sectionHeader("录音分组"));
        groups = column();
        header.addView(groups);
        renderGroups();

        // 6. Search Bar & Segmented Filter
        header.addView(sectionHeader("筛选与搜索"));
        header.addView(buildSearchBar());

        LinearLayout.LayoutParams filterLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        filterLp.setMargins(0, dp(10), 0, dp(6));
        header.addView(buildSegmentedFilter(), filterLp);

        filterSummary = new TextView(this);
        filterSummary.setText("筛选结果加载中…");
        filterSummary.setTextSize(12);
        filterSummary.setTextColor(COLOR_SECONDARY_TEXT);
        filterSummary.setPadding(dp(4), dp(4), dp(4), dp(4));
        header.addView(filterSummary);

        emptyState = new TextView(this);
        emptyState.setText("暂无可显示的录音");
        emptyState.setTextSize(13);
        emptyState.setTextColor(COLOR_SECONDARY_TEXT);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setPadding(dp(16), dp(20), dp(16), dp(20));
        emptyState.setBackground(cardDrawable(COLOR_CARD, COLOR_CARD_STROKE, 1, 14));
        emptyState.setVisibility(View.GONE);
        header.addView(emptyState);

        clearFilters = new Button(this);
        clearFilters.setText("✕ 重置搜索与筛选");
        clearFilters.setContentDescription("清除搜索、状态筛选和录音分组");
        clearFilters.setTextColor(COLOR_SECONDARY_TEXT);
        clearFilters.setTextSize(12);
        clearFilters.setMinHeight(dp(36));
        clearFilters.setBackground(cardDrawable(COLOR_PILL_NEUTRAL, COLOR_CARD_STROKE, 1, 12));
        clearFilters.setVisibility(View.GONE);
        clearFilters.setOnClickListener(v -> {
            handler.removeCallbacks(filterRunnable);
            selectedGroup = ALL_GROUP;
            if (groupScrollView != null) groupScrollView.smoothScrollTo(0, 0);
            search.setText("");
            setFilterMode(0);
            renderGroups();
            applyFilter();
        });
        LinearLayout.LayoutParams cfLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        cfLp.setMargins(0, dp(8), 0, 0);
        header.addView(clearFilters, cfLp);

        list.addHeaderView(header);
        adapter = new BaseAdapter() {
            public int getCount() { return visible.size(); }
            public Object getItem(int p) { return visible.get(p); }
            public long getItemId(int p) { return visible.get(p).getLong("id"); }
            public View getView(int position, View recycled, ViewGroup parent) { return row(visible.get(position)); }
        };
        list.setAdapter(adapter);
        setContentView(list);
        list.requestApplyInsets();
        handler.post(this::showOnboardingIfNeeded);
    }

    private View settingRow(String title, String subtitle, Switch toggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        LinearLayout textCol = column();
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(COLOR_PRIMARY_TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextSize(12);
        s.setTextColor(COLOR_SECONDARY_TEXT);
        s.setPadding(0, dp(2), 0, 0);
        textCol.addView(s);

        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private View buildMetricsCard() {
        LinearLayout card = column();
        card.setBackground(cardDrawable(COLOR_CARD, COLOR_CARD_STROKE, 1, 16));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("录音处理统计");
        title.setTextSize(14);
        title.setTextColor(COLOR_PRIMARY_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        topRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        metricBadge = new TextView(this);
        metricBadge.setText("实时数据");
        metricBadge.setTextSize(11);
        metricBadge.setTextColor(COLOR_SUCCESS_TEXT);
        metricBadge.setBackground(cardDrawable(COLOR_SUCCESS_BG, 0, 0, 8));
        metricBadge.setPadding(dp(8), dp(2), dp(8), dp(2));
        topRow.addView(metricBadge);
        card.addView(topRow);

        LinearLayout grid1 = new LinearLayout(this);
        grid1.setOrientation(LinearLayout.HORIZONTAL);
        grid1.setPadding(0, dp(12), 0, dp(4));

        LinearLayout cellTotal = metricCell("全部录音", COLOR_PRIMARY_TEXT);
        metricTotal = (TextView) cellTotal.getChildAt(0);
        grid1.addView(cellTotal, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout cellTrans = metricCell("已转写", COLOR_SUCCESS_TEXT);
        metricTranscribed = (TextView) cellTrans.getChildAt(0);
        grid1.addView(cellTrans, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(grid1);

        LinearLayout grid2 = new LinearLayout(this);
        grid2.setOrientation(LinearLayout.HORIZONTAL);
        grid2.setPadding(0, dp(4), 0, dp(8));

        LinearLayout cellSumm = metricCell("已摘要", COLOR_ACCENT);
        metricSummarized = (TextView) cellSumm.getChildAt(0);
        grid2.addView(cellSumm, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout cellUnfin = metricCell("待处理", COLOR_WARNING_TEXT);
        metricUnfinished = (TextView) cellUnfin.getChildAt(0);
        grid2.addView(cellUnfin, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(grid2);

        card.addView(divider());

        metricFooter = new TextView(this);
        metricFooter.setText("并发负载：转写 0 / 3   ·   摘要 0 / 1   ·   短文本跳过 0");
        metricFooter.setTextSize(11);
        metricFooter.setTextColor(COLOR_SECONDARY_TEXT);
        metricFooter.setPadding(0, dp(6), 0, dp(2));
        card.addView(metricFooter);

        return card;
    }

    private LinearLayout metricCell(String label, int numColor) {
        LinearLayout cell = column();
        TextView num = new TextView(this);
        num.setText("-");
        num.setTextSize(22);
        num.setTextColor(numColor);
        num.setTypeface(Typeface.DEFAULT_BOLD);
        cell.addView(num);

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextSize(12);
        lbl.setTextColor(COLOR_SECONDARY_TEXT);
        lbl.setPadding(0, dp(2), 0, 0);
        cell.addView(lbl);
        return cell;
    }

    private View buildSearchBar() {
        LinearLayout searchBar = new LinearLayout(this);
        searchBar.setOrientation(LinearLayout.HORIZONTAL);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackground(cardDrawable(COLOR_CARD, COLOR_CARD_STROKE, 1, 22));
        searchBar.setPadding(dp(14), 0, dp(10), 0);
        searchBar.setMinimumHeight(dp(44));

        search = new EditText(this);
        search.setSingleLine();
        search.setHint("搜索文件名或路径…");
        search.setContentDescription("搜索录音文件名或源路径");
        search.setTextColor(COLOR_PRIMARY_TEXT);
        search.setHintTextColor(COLOR_SECONDARY_TEXT);
        search.setBackground(null);
        search.setTextSize(13);
        search.setPadding(0, 0, 0, 0);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((v, actionId, event) -> {
            handler.removeCallbacks(filterRunnable);
            applyFilter();
            return false;
        });
        searchBar.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView searchClear = new TextView(this);
        searchClear.setText("✕");
        searchClear.setTextColor(COLOR_SECONDARY_TEXT);
        searchClear.setTextSize(14);
        searchClear.setGravity(Gravity.CENTER);
        searchClear.setPadding(dp(8), dp(4), dp(8), dp(4));
        searchClear.setVisibility(View.GONE);
        searchClear.setOnClickListener(v -> search.setText(""));
        searchBar.addView(searchClear);

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int f) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable e) {
                searchClear.setVisibility(e.length() > 0 ? View.VISIBLE : View.GONE);
                handler.removeCallbacks(filterRunnable);
                handler.postDelayed(filterRunnable, 250);
            }
        });
        return searchBar;
    }

    private View buildSegmentedFilter() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(cardDrawable(COLOR_CARD, COLOR_CARD_STROKE, 1, 14));
        bar.setPadding(dp(3), dp(3), dp(3), dp(3));

        String[] labels = {"全部", "处理中", "未完成", "全部完成"};
        filterSegments = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView btn = new TextView(this);
            btn.setText(labels[i]);
            btn.setTextSize(13);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(0, dp(8), 0, dp(8));
            boolean active = (i == filterMode);
            btn.setTextColor(active ? COLOR_PRIMARY_TEXT : COLOR_SECONDARY_TEXT);
            btn.setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            if (active) btn.setBackground(cardDrawable(COLOR_ACCENT, 0, 0, 11));
            btn.setOnClickListener(v -> setFilterMode(index));
            bar.addView(btn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            filterSegments[i] = btn;
        }
        return bar;
    }

    private void setFilterMode(int mode) {
        filterMode = mode;
        for (int i = 0; i < filterSegments.length; i++) {
            boolean active = (i == mode);
            filterSegments[i].setTextColor(active ? COLOR_PRIMARY_TEXT : COLOR_SECONDARY_TEXT);
            filterSegments[i].setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            filterSegments[i].setBackground(active ? cardDrawable(COLOR_ACCENT, 0, 0, 11) : null);
        }
        handler.removeCallbacks(filterRunnable);
        applyFilter();
    }

    private void renderGroups() {
        if (groups == null) return;
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        counts.put(ALL_GROUP, all.size());
        for (Bundle b : all) {
            String name = b.getString("group", "未分组");
            if (name == null || name.trim().isEmpty()) name = "未分组";
            counts.put(name, counts.containsKey(name) ? counts.get(name) + 1 : 1);
        }
        if (!counts.containsKey(selectedGroup)) selectedGroup = ALL_GROUP;

        if (counts.equals(currentGroupCounts) && Objects.equals(selectedGroup, lastRenderedSelectedGroup)) {
            return;
        }

        if (groupScrollView == null) {
            groupScrollView = new HorizontalScrollView(this);
            groupScrollView.setHorizontalScrollBarEnabled(false);
            groupRow = new LinearLayout(this);
            groupRow.setOrientation(LinearLayout.HORIZONTAL);
            groupScrollView.addView(groupRow, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            groups.addView(groupScrollView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        boolean sameKeys = counts.keySet().equals(currentGroupCounts.keySet())
                && groupRow.getChildCount() == counts.size();

        if (sameKeys) {
            int index = 0;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                View child = groupRow.getChildAt(index++);
                if (child instanceof TextView) {
                    TextView chip = (TextView) child;
                    String key = entry.getKey(), name = ALL_GROUP.equals(key) ? "全部录音" : key;
                    boolean selected = key.equals(selectedGroup);
                    chip.setText(name + "  " + entry.getValue());
                    chip.setTextColor(selected ? COLOR_PRIMARY_TEXT : COLOR_SECONDARY_TEXT);
                    chip.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                    chip.setContentDescription("录音分组 " + name + "，" + entry.getValue() + " 条" + (selected ? "，已选中" : ""));
                    chip.setBackground(selected ? cardDrawable(COLOR_ACCENT, 0, 0, 17) : cardDrawable(COLOR_CARD, COLOR_CARD_STROKE, 1, 17));
                    chip.setOnClickListener(v -> {
                        selectedGroup = key;
                        renderGroups();
                        applyFilter();
                    });
                }
            }
        } else {
            final int savedScrollX = groupScrollView.getScrollX();
            groupRow.removeAllViews();
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                String key = entry.getKey(), name = ALL_GROUP.equals(key) ? "全部录音" : key;
                boolean selected = key.equals(selectedGroup);
                TextView chip = new TextView(this);
                chip.setText(name + "  " + entry.getValue());
                chip.setTextSize(13);
                chip.setTextColor(selected ? COLOR_PRIMARY_TEXT : COLOR_SECONDARY_TEXT);
                chip.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                chip.setGravity(Gravity.CENTER);
                chip.setMinHeight(dp(34));
                chip.setPadding(dp(14), 0, dp(14), 0);
                chip.setContentDescription("录音分组 " + name + "，" + entry.getValue() + " 条" + (selected ? "，已选中" : ""));
                chip.setBackground(selected ? cardDrawable(COLOR_ACCENT, 0, 0, 17) : cardDrawable(COLOR_CARD, COLOR_CARD_STROKE, 1, 17));
                chip.setOnClickListener(v -> {
                    selectedGroup = key;
                    renderGroups();
                    applyFilter();
                });
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                p.setMargins(0, dp(4), dp(8), dp(6));
                groupRow.addView(chip, p);
            }
            if (savedScrollX > 0) {
                groupScrollView.post(() -> {
                    if (groupScrollView != null) groupScrollView.scrollTo(savedScrollX, 0);
                });
            }
        }
        currentGroupCounts.clear();
        currentGroupCounts.putAll(counts);
        lastRenderedSelectedGroup = selectedGroup;
    }

    private Button actionButton(String title) {
        Button v = new Button(this);
        v.setText(title);
        v.setAllCaps(false);
        v.setTextColor(COLOR_PRIMARY_TEXT);
        v.setTextSize(12);
        v.setMinHeight(dp(36));
        v.setMinWidth(0);
        v.setPadding(dp(6), 0, dp(6), 0);
        v.setBackground(rippleDrawable(COLOR_PILL_NEUTRAL, COLOR_CARD_STROKE, COLOR_CARD_STROKE, 10));
        return v;
    }

    private TextView statusPill(String label, int textColor, int bgColor) {
        TextView v = new TextView(this);
        v.setText(label);
        v.setTextSize(11);
        v.setTextColor(textColor);
        v.setBackground(cardDrawable(bgColor, 0, 0, 10));
        v.setPadding(dp(10), dp(3), dp(10), dp(3));
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setContentDescription(label);
        return v;
    }

    private void openNative(Bundle row, String key, String unavailable) {
        PendingIntent target = row.getParcelable(key);
        try {
            if (target == null) throw new IllegalStateException(unavailable);
            ActivityOptions options = ActivityOptions.makeBasic();
            if (Build.VERSION.SDK_INT >= 34) options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            target.send(this, 0, null, null, null, null, options.toBundle());
        } catch (Exception e) { Toast.makeText(this, "打开失败：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void copy(String label, String value) {
        getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, label + "已复制", Toast.LENGTH_SHORT).show();
    }

    private boolean containsAny(String value, String... parts) {
        if (value == null) return false;
        for (String part : parts) if (value.contains(part)) return true;
        return false;
    }

    private boolean shortText(Bundle row) {
        return row.getBoolean("summarySkipped") || containsAny(row.getString("summary", ""), "100013", "文本不足", "少于 100 字");
    }

    private boolean failed(String state) {
        return containsAny(state, "失败", "拒绝", "中断", "需重试", "异常", "错误", "无法", "未收到启动回执");
    }

    private boolean waiting(String state) {
        return containsAny(state, "等待", "排队", "待生成", "待转写", "已提交", "处理中", "转写中", "生成中");
    }

    private String transcriptionLabel(Bundle row) {
        String state = row.getString("transcription", "状态未知");
        if (row.getBoolean("complete")) return "● 转写完成";
        if (failed(state) || !row.getString("error", "").trim().isEmpty()) return "● 转写失败";
        if (waiting(state)) return "● 转写排队中";
        if (row.getBoolean("active")) return "● 转写进行中";
        return row.getBoolean("retry") ? "● 转写待重试" : "● " + state;
    }

    private int transcriptionColor(Bundle row) {
        String state = row.getString("transcription", "");
        if (row.getBoolean("complete")) return COLOR_SUCCESS_TEXT;
        if (failed(state) || !row.getString("error", "").trim().isEmpty()) return COLOR_ERROR_TEXT;
        if (row.getBoolean("active") || waiting(state)) return COLOR_WARNING_TEXT;
        return COLOR_SECONDARY_TEXT;
    }

    private int transcriptionBg(Bundle row) {
        String state = row.getString("transcription", "");
        if (row.getBoolean("complete")) return COLOR_SUCCESS_BG;
        if (failed(state) || !row.getString("error", "").trim().isEmpty()) return COLOR_ERROR_BG;
        if (row.getBoolean("active") || waiting(state)) return COLOR_WARNING_BG;
        return COLOR_PILL_NEUTRAL;
    }

    private String summaryLabel(Bundle row) {
        String state = row.getString("summary", "状态未知");
        if (shortText(row)) return "○ 摘要已跳过(<100字)";
        if (row.getBoolean("summaryDone")) return "● 摘要完成";
        if (failed(state)) return "● 摘要失败";
        if (waiting(state)) return "● 摘要排队中";
        if (row.getBoolean("active")) return "● 摘要生成中";
        return "○ " + state;
    }

    private int summaryColor(Bundle row) {
        String state = row.getString("summary", "");
        if (shortText(row)) return COLOR_SECONDARY_TEXT;
        if (row.getBoolean("summaryDone")) return COLOR_SUCCESS_TEXT;
        if (failed(state)) return COLOR_ERROR_TEXT;
        if (row.getBoolean("active") || waiting(state)) return COLOR_WARNING_TEXT;
        return COLOR_SECONDARY_TEXT;
    }

    private int summaryBg(Bundle row) {
        String state = row.getString("summary", "");
        if (shortText(row)) return COLOR_PILL_NEUTRAL;
        if (row.getBoolean("summaryDone")) return COLOR_SUCCESS_BG;
        if (failed(state)) return COLOR_ERROR_BG;
        if (row.getBoolean("active") || waiting(state)) return COLOR_WARNING_BG;
        return COLOR_PILL_NEUTRAL;
    }

    private void showResults(Bundle row) {
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayList<String> targets = new ArrayList<>();
        if (row.getParcelable("openTranscript") != null) { labels.add("查看转写结果"); targets.add("openTranscript"); }
        if (row.getParcelable("openSummary") != null) { labels.add("查看 AI 摘要"); targets.add("openSummary"); }
        StringBuilder message = new StringBuilder("转写\n").append(row.getString("transcription", "状态未知"))
                .append("\n\nAI 摘要\n").append(row.getString("summary", "状态未知"));
        if (shortText(row)) message.append("\n\n100013 表示文本少于 100 字，摘要服务已正常跳过，不需要重试。");
        String error = row.getString("error", "");
        if (!error.trim().isEmpty()) message.append("\n\n失败详情\n").append(error);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("转写与摘要").setMessage(message).setNegativeButton("关闭", null);
        if (!labels.isEmpty()) dialog.setItems(labels.toArray(new String[0]), (d, which) -> openNative(row, targets.get(which), "结果尚未生成"));
        dialog.show();
    }

    private void showFileLocations(Bundle row) {
        final String source = row.getString("path", "");
        final String transcript = row.getString("transcriptPath", "");
        ArrayList<String> options = new ArrayList<>();
        options.add("复制原录音位置");
        if (!transcript.trim().isEmpty()) options.add("复制转写文件位置");
        StringBuilder message = new StringBuilder("原录音\n").append(source);
        if (!transcript.trim().isEmpty()) message.append("\n\n转写文件\n").append(transcript);
        else message.append("\n\n转写文件\n尚未生成");
        message.append("\n\nAI 摘要由录音机本地数据库保存，没有单独文件；可点‘查看摘要’打开原应用页面。");
        new AlertDialog.Builder(this).setTitle("文件位置")
                .setMessage(message).setItems(options.toArray(new String[0]), (d, which) -> {
                    if (which == 0) copy("原录音位置", source); else copy("转写文件位置", transcript);
                }).setNegativeButton("关闭", null).show();
    }

    private void confirmSummaryRetry(Bundle row) {
        new AlertDialog.Builder(this).setTitle("确认重新生成摘要")
                .setMessage("模块暂时无法确认原 AI 摘要服务是否仍在处理这条录音。继续后会先向原服务核实；只有明确空闲才会重新请求摘要。")
                .setNegativeButton("取消", null)
                .setNeutralButton("强制重新生成", (d, w) -> confirmForcedSummaryRetry(row))
                .setPositiveButton("核实后重新生成", (d, w) -> request("retry_summary", row.getLong("id"))).show();
    }

    private void confirmForcedSummaryRetry(Bundle row) {
        new AlertDialog.Builder(this).setTitle("强制重新生成摘要")
                .setMessage("原摘要服务仍无法核实。强制操作可能与尚未显示的旧请求重复，只应在你确认原服务已经停止时使用。")
                .setNegativeButton("取消", null)
                .setPositiveButton("强制重新生成", (d, w) -> request("retry_summary_force", row.getLong("id"))).show();
    }

    private void showOnboardingIfNeeded() {
        if (ModuleSettings.onboardingComplete(this) || isFinishing()) return;
        new AlertDialog.Builder(this).setTitle("开始前确认")
                .setMessage("本模块只处理录音机数据库中已有的录音。\n\n启用后，会把未处理录音交给原录音机和 AI 语音摘记；原应用可能根据账户和网络规则处理内容。首次扫描可能包含历史录音。\n\n转写最多同时三条，AI 摘要按原服务限制逐条生成。")
                .setNegativeButton("保持关闭", (d, w) -> finishOnboarding(false))
                .setPositiveButton("同意并启用", (d, w) -> finishOnboarding(true))
                .setCancelable(false).show();
    }

    private void finishOnboarding(boolean turnOn) {
        applyingOnboarding = true;
        enabled.setChecked(turnOn);
        getSharedPreferences(ModuleSettings.PREFS, MODE_PRIVATE).edit()
                .putBoolean(ModuleSettings.ONBOARDING_COMPLETE, true)
                .putBoolean(ModuleSettings.ENABLED, turnOn)
                .putBoolean(ModuleSettings.AI_SUMMARY, summary.isChecked())
                .putBoolean(ModuleSettings.RESPECT_CONDITIONS, conditions.isChecked()).apply();
        applyingOnboarding = false;
        request(turnOn ? "scan" : "refresh", 0);
    }

    private void save() {
        if (applyingOnboarding) return;
        getSharedPreferences(ModuleSettings.PREFS, MODE_PRIVATE).edit().putBoolean(ModuleSettings.ENABLED, enabled.isChecked())
                .putBoolean(ModuleSettings.AI_SUMMARY, summary.isChecked()).putBoolean(ModuleSettings.RESPECT_CONDITIONS, conditions.isChecked()).apply();
        request("refresh", 0);
    }

    private Bundle call(String method, Bundle extras) { return getContentResolver().call(ModuleSettings.URI, method, null, extras); }

    private void request(String op, long id) {
        Bundle cmd = new Bundle(); cmd.putString("op", op); cmd.putLong("id", id);
        requested = call("request", cmd).getLong("request");
        final long requestId = requested;
        statusEpoch++;
        snapshotStale = true;
        notifyDataChanged();
        if (statusDot != null) {
            statusDot.setText("● 正在核对录音队列…");
            statusDot.setTextColor(COLOR_WARNING_TEXT);
        }
        if (scan != null) {
            scan.setEnabled(false);
            scan.setText("正在检查录音队列…");
        }
        Bundle settings = call("get", null);
        Intent wake = new Intent(MainHook.SCAN_NOW_ACTION).setPackage(MainHook.TARGET)
                .putExtra("token", settings.getString("token"));
        if (Build.VERSION.SDK_INT >= 34) {
            Bundle options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
            sendBroadcast(wake, null, options);
        } else sendBroadcast(wake);
        handler.postDelayed(() -> {
            if (resumed && requested == requestId) {
                if (scan != null) {
                    scan.setEnabled(true);
                    scan.setText("⟳ 立即检查与处理录音");
                }
                if (statusSub != null) statusSub.setText("录音机尚未确认请求。请检查 LSPosed 两个作用域；若录音机未运行，请先打开它一次或等待系统后台检查。");
            }
        }, 20000);
        loadStatus();
    }

    private void loadStatus() {
        if (!resumed || loading) return;
        loading = true;
        final long renderedUpdated = updated;
        final boolean hadSnapshot = snapshotLoaded;
        final boolean renderedStale = snapshotStale;
        final long renderedGeneration = generation;
        final long requestEpoch = statusEpoch;
        io.execute(() -> {
            try {
                Bundle status = call("status", null);
                if (status == null) throw new IllegalStateException("模块设置服务没有返回状态");
                long incomingUpdated = status.getLong("updated");
                long incomingGeneration = status.getLong("generation", 0L);
                boolean incomingStale = status.getBoolean("stale") || (status.containsKey("rowsCurrent") && !status.getBoolean("rowsCurrent"));
                boolean needsRows = !incomingStale && (!hadSnapshot || incomingUpdated != renderedUpdated
                        || incomingStale != renderedStale || incomingGeneration != renderedGeneration);
                ArrayList<Bundle> data = null;
                int total = status.getInt("total");
                boolean generationChanged = false;
                if (needsRows) {
                    data = new ArrayList<>();
                    ArrayList<Bundle> page = status.getParcelableArrayList("rows");
                    if (page != null) data.addAll(page);
                    for (int offset = 50; offset < total; offset += 50) {
                        Bundle args = new Bundle();
                        args.putInt("offset", offset);
                        args.putLong("generation", incomingGeneration);
                        Bundle pageStatus = call("status", args);
                        if (pageStatus == null || pageStatus.getLong("generation", 0L) != incomingGeneration) {
                            generationChanged = true;
                            break;
                        }
                        page = pageStatus.getParcelableArrayList("rows");
                        if (page != null) data.addAll(page);
                    }
                }
                if (generationChanged) {
                    handler.post(() -> {
                        loading = false;
                        if (resumed) loadStatus();
                    });
                    return;
                }
                final ArrayList<Bundle> rows = data;
                handler.post(() -> {
                    loading = false;
                    if (!resumed) return;
                    if (requestEpoch != statusEpoch) { loadStatus(); return; }
                    boolean rowsChanged = !snapshotLoaded || incomingUpdated != updated || incomingStale != snapshotStale || incomingGeneration != generation;
                    updated = incomingUpdated;
                    long age = System.currentTimeMillis() - updated;
                    generation = incomingGeneration;
                    String message = status.getString("message", "尚未收到录音机回执，点扫描建立连接");
                    boolean connected = updated > 0 && age <= 45000;
                    if (updated > 0 && age > 45000) message = "连接暂时中断 · 正在使用上次快照";
                    if (updated > 0 && !BuildConfig.VERSION_NAME.equals(status.getString("version"))) message = "录音机内仍是旧模块，请重启录音机进程";
                    if (requested != 0 && status.getLong("request") >= requested) {
                        requested = 0;
                        if (scan != null) {
                            scan.setEnabled(true);
                            scan.setText("⟳ 立即检查与处理录音");
                        }
                    }
                    boolean stale = incomingStale;
                    boolean stalenessChanged = snapshotStale != stale;
                    snapshotStale = stale;

                    SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.CHINA);
                    SimpleDateFormat clockSec = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

                    String scanState = status.getString("scanState", "idle");
                    String scanLabel = "running".equals(scanState) ? "扫描中" : "success".equals(scanState) ? "已完成"
                            : "failed".equals(scanState) ? "扫描失败" : "interrupted".equals(scanState) ? "系统中断"
                            : "paused".equals(scanState) ? "已暂停" : "就绪";

                    if (statusDot != null) {
                        if (!connected) {
                            statusDot.setText("● " + (updated == 0 ? "等待首次同步" : "连接中断"));
                            statusDot.setTextColor(updated == 0 ? COLOR_WARNING_TEXT : COLOR_ERROR_TEXT);
                        } else if ("running".equals(scanState)) {
                            statusDot.setText("● 正在扫描录音…");
                            statusDot.setTextColor(COLOR_WARNING_TEXT);
                        } else {
                            statusDot.setText("● 后台巡检正常");
                            statusDot.setTextColor(COLOR_SUCCESS_TEXT);
                        }
                    }

                    if (statusSub != null) {
                        StringBuilder sub = new StringBuilder();
                        sub.append(message);
                        if (updated > 0) sub.append("\n同步时间：").append(clockSec.format(new Date(updated)));
                        long scheduledAt = status.getLong("scheduledAt");
                        if (scheduledAt > 0) sub.append("  ·  核验于 ").append(clock.format(new Date(scheduledAt)));
                        long bridgeReady = status.getLong("bridgeReadyAt");
                        if (bridgeReady > 0) sub.append("  ·  桥接就绪");
                        String schedule = status.getString("schedule", "");
                        if (!schedule.isEmpty()) sub.append("\n巡检策略：").append(schedule);
                        long scanAttempt = status.getLong("scanAttemptAt");
                        if (scanAttempt > 0) sub.append("  ·  最近检查: ").append(scanLabel);
                        String scanError = status.getString("lastScanError", "");
                        if (!scanError.isEmpty()) sub.append(" (").append(scanError).append(")");
                        statusSub.setText(sub.toString());
                    }

                    int shownTotal = stale ? status.getInt("cached_total") : total;
                    int transcribed = stale ? status.getInt("cached_transcribed") : status.getInt("transcribed");
                    int summarized = stale ? status.getInt("cached_summarized") : status.getInt("summarized");
                    int skipped = stale ? status.getInt("cached_skipped") : status.getInt("skipped");
                    int unfinished = stale ? status.getInt("cached_unfinished") : status.getInt("unfinished");
                    int running = stale ? status.getInt("cached_running") : status.getInt("running");
                    int summaries = stale ? status.getInt("cached_summaries") : status.getInt("summaries");

                    if (metricTotal != null) metricTotal.setText(String.valueOf(shownTotal));
                    if (metricTranscribed != null) metricTranscribed.setText(String.valueOf(transcribed));
                    if (metricSummarized != null) metricSummarized.setText(String.valueOf(summarized));
                    if (metricUnfinished != null) metricUnfinished.setText(String.valueOf(unfinished));
                    if (metricFooter != null) {
                        metricFooter.setText("并发负载：转写 " + running + " / 3   ·   摘要 " + summaries + " / 1   ·   短文本跳过 " + skipped);
                    }
                    if (metricBadge != null) {
                        metricBadge.setText(stale ? "上次快照" : "实时数据");
                        metricBadge.setTextColor(stale ? COLOR_WARNING_TEXT : COLOR_SUCCESS_TEXT);
                        metricBadge.setBackground(cardDrawable(stale ? COLOR_WARNING_BG : COLOR_SUCCESS_BG, 0, 0, 8));
                    }

                    if (rowsChanged && rows != null) {
                        all.clear();
                        all.addAll(rows);
                        snapshotLoaded = true;
                        renderGroups();
                        applyFilter();
                    } else if (stalenessChanged) {
                        notifyDataChanged();
                    }
                    handler.removeCallbacks(refresh);
                    handler.postDelayed(refresh, 3000);
                });
            } catch (Exception e) {
                handler.post(() -> {
                    loading = false;
                    if (!resumed) return;
                    if (requestEpoch != statusEpoch) { loadStatus(); return; }
                    all.clear();
                    visible.clear();
                    snapshotLoaded = false;
                    snapshotStale = true;
                    updated = 0L;
                    generation = 0L;
                    renderGroups();
                    applyFilter();
                    if (statusDot != null) {
                        statusDot.setText("● 状态读取失败");
                        statusDot.setTextColor(COLOR_ERROR_TEXT);
                    }
                    if (statusSub != null) statusSub.setText(e.getMessage());
                    if (scan != null) {
                        scan.setEnabled(true);
                        scan.setText("⟳ 立即检查与处理录音");
                    }
                    handler.postDelayed(refresh, 5000);
                });
            }
        });
    }

    private void notifyDataChanged() {
        if (adapter == null) return;
        final boolean searchFocused = search != null && search.hasFocus();
        final int selStart = searchFocused ? search.getSelectionStart() : -1;
        final int selEnd = searchFocused ? search.getSelectionEnd() : -1;
        adapter.notifyDataSetChanged();
        if (searchFocused && list != null) {
            if (!search.hasFocus()) search.requestFocus();
            list.post(() -> {
                if (!isFinishing() && !isDestroyed() && search != null) {
                    if (!search.hasFocus()) search.requestFocus();
                    int len = search.getText().length();
                    if (selStart >= 0 && selEnd >= 0) {
                        int s = Math.max(0, Math.min(selStart, len));
                        int e = Math.max(0, Math.min(selEnd, len));
                        if (s <= e) search.setSelection(s, e);
                        else search.setSelection(e, s);
                    }
                }
            });
        }
    }

    private void applyFilter() {
        handler.removeCallbacks(filterRunnable);
        if (adapter == null) return;
        String q = search != null ? search.getText().toString().toLowerCase(Locale.ROOT) : "";
        int mode = filterMode;
        visible.clear();
        for (Bundle b : all) {
            boolean summarySatisfied = TaskPolicy.summarySatisfied(b.getBoolean("summaryDone"), b.getBoolean("summarySkipped"));
            boolean done = b.getBoolean("complete") && summarySatisfied;
            if ((mode == 1 && !b.getBoolean("active")) || (mode == 2 && done) || (mode == 3 && !done)) continue;
            if (!ALL_GROUP.equals(selectedGroup) && !selectedGroup.equals(b.getString("group", "未分组"))) continue;
            if (!(b.getString("name", "") + b.getString("path", "")).toLowerCase(Locale.ROOT).contains(q)) continue;
            visible.add(b);
        }
        String[] modes = {"全部状态", "处理中", "未完成", "全部完成"};
        String group = ALL_GROUP.equals(selectedGroup) ? "全部录音" : selectedGroup;
        filterSummary.setText("共 " + all.size() + " 条 · 当前显示 " + visible.size() + " 条  ·  " + group + "  ·  " + modes[Math.max(0, Math.min(mode, modes.length - 1))]);
        boolean empty = visible.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            boolean filtered = !q.isEmpty() || mode != 0 || !ALL_GROUP.equals(selectedGroup);
            String hint = all.isEmpty() ? "暂未发现可显示的录音。完成一条录音后再扫描。" : "没有符合当前条件的录音。";
            emptyState.setText(hint + (filtered ? " 可点击下方按钮重置筛选。" : ""));
            emptyState.setContentDescription(emptyState.getText());
            clearFilters.setVisibility(filtered ? View.VISIBLE : View.GONE);
        } else {
            clearFilters.setVisibility(View.GONE);
        }
        notifyDataChanged();
    }

    private View row(Bundle b) {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(cardDrawable(COLOR_CARD, COLOR_CARD_STROKE, 1, 16));

        // Row 1: Header (Title + Group Badge)
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);

        String name = b.getString("name", "未命名录音");
        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(16);
        title.setTextColor(COLOR_PRIMARY_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setContentDescription("录音 " + name);
        headRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        String group = b.getString("group", "未分组");
        if (group == null || group.trim().isEmpty()) group = "未分组";
        TextView groupPill = new TextView(this);
        groupPill.setText(group);
        groupPill.setTextSize(11);
        groupPill.setTextColor(COLOR_SECONDARY_TEXT);
        groupPill.setBackground(cardDrawable(COLOR_PILL_NEUTRAL, 0, 0, 8));
        groupPill.setPadding(dp(8), dp(2), dp(8), dp(2));
        LinearLayout.LayoutParams gpLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gpLp.setMargins(dp(8), 0, 0, 0);
        headRow.addView(groupPill, gpLp);
        card.addView(headRow);

        // Row 2: Metadata (Duration + Date)
        StringBuilder meta = new StringBuilder();
        long seconds = b.getLong("duration") / 1000;
        if (seconds > 0) meta.append(String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60));
        long date = b.getLong("date");
        if (date > 0) {
            if (meta.length() > 0) meta.append("   ·   ");
            meta.append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(date)));
        }
        if (meta.length() > 0) {
            TextView metaView = new TextView(this);
            metaView.setText(meta.toString());
            metaView.setTextSize(12);
            metaView.setTextColor(COLOR_SECONDARY_TEXT);
            metaView.setPadding(0, dp(4), 0, dp(4));
            card.addView(metaView);
        }

        // Row 3: Status Badge Pills
        LinearLayout badgesRow = new LinearLayout(this);
        badgesRow.setOrientation(LinearLayout.HORIZONTAL);
        badgesRow.setPadding(0, dp(4), 0, dp(8));

        TextView tBadge = statusPill(transcriptionLabel(b), transcriptionColor(b), transcriptionBg(b));
        badgesRow.addView(tBadge);

        TextView sBadge = statusPill(summaryLabel(b), summaryColor(b), summaryBg(b));
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.setMargins(dp(8), 0, 0, 0);
        badgesRow.addView(sBadge, sLp);
        card.addView(badgesRow);

        // Row 4: Action Buttons
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(4), 0, 0);

        Button results = actionButton("结果详情");
        results.setContentDescription("查看 " + name + " 的转写和摘要结果");
        results.setOnClickListener(v -> showResults(b));
        actions.addView(results, new LinearLayout.LayoutParams(0, dp(36), 1));

        Button open = actionButton("录音机定位");
        open.setContentDescription("在录音机中打开 " + name);
        open.setOnClickListener(v -> openNative(b, "open", "请重新扫描获取定位入口"));
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(0, dp(36), 1);
        openLp.setMargins(dp(8), 0, dp(8), 0);
        actions.addView(open, openLp);

        Button location = actionButton("文件位置");
        location.setContentDescription("查看 " + name + " 的原录音和转写文件位置");
        location.setOnClickListener(v -> showFileLocations(b));
        actions.addView(location, new LinearLayout.LayoutParams(0, dp(36), 1));
        card.addView(actions);

        // Conditional Retry / Confirmation Buttons
        if (b.getBoolean("summaryNeedsConfirmation")) {
            Button confirm = new Button(this);
            confirm.setText("⚡ 确认重新生成摘要");
            confirm.setAllCaps(false);
            confirm.setTextColor(COLOR_WARNING_TEXT);
            confirm.setTextSize(13);
            confirm.setMinHeight(dp(40));
            confirm.setBackground(cardDrawable(COLOR_WARNING_BG, COLOR_WARNING_TEXT, 1, 10));
            confirm.setEnabled(enabled.isChecked() && summary.isChecked());
            confirm.setOnClickListener(v -> confirmSummaryRetry(b));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
            p.setMargins(0, dp(8), 0, 0);
            card.addView(confirm, p);
        }

        if (b.getBoolean("retry")) {
            Button retry = new Button(this);
            retry.setText("↻ 重新提交转写任务");
            retry.setAllCaps(false);
            retry.setTextColor(Color.WHITE);
            retry.setTextSize(13);
            retry.setMinHeight(dp(40));
            retry.setBackground(rippleDrawable(COLOR_ACCENT, COLOR_ACCENT_PRESSED, 0, 10));
            retry.setEnabled(enabled.isChecked() && !snapshotStale);
            retry.setOnClickListener(v -> request("retry", b.getLong("id")));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
            p.setMargins(0, dp(8), 0, 0);
            card.addView(retry, p);
        }

        return card;
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        loadStatus();
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(filterRunnable);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        io.shutdown();
    }
}
