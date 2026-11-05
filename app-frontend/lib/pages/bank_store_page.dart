import 'package:flutter/material.dart';
import '../core/api.dart';
import '../core/ui.dart';

const _subjects = ['语文', '数学', '英语', '物理', '化学', '生物', '历史', '地理', '政治', '其他'];
const _tagColorMap = {
  'blue': Color(0xFF2C6BD6), 'purple': Color(0xFF7C4DC4), 'green': Color(0xFF2E7D5B),
  'gold': Color(0xFFB07B2A), 'cyan': Color(0xFF0E8A9E), 'magenta': Color(0xFFC0427C),
  'orange': Color(0xFFC46A1E),
};

/// 题库商店（PRD FR-S08~S12）：商店浏览/搜索/筛选/添加 + 我的题库分配管理
class BankStorePage extends StatefulWidget {
  const BankStorePage({super.key});
  @override
  State<BankStorePage> createState() => _BankStorePageState();
}

class _BankStorePageState extends State<BankStorePage> with SingleTickerProviderStateMixin {
  late final TabController _tab = TabController(length: 2, vsync: this);
  final _search = TextEditingController();
  final _scroll = ScrollController();

  int? _subject;
  int? _grade;
  List<Map<String, dynamic>> _items = [];
  int _page = 1;
  int _total = 0;
  bool _loading = false;
  String? _tip;

  List<Map<String, dynamic>> _mine = [];

  @override
  void initState() {
    super.initState();
    _loadStore(reset: true);
    _scroll.addListener(() {
      if (_scroll.position.pixels > _scroll.position.maxScrollExtent - 200 &&
          !_loading && _items.length < _total) {
        _loadStore();
      }
    });
  }

  @override
  void dispose() {
    _tab.dispose(); _search.dispose(); _scroll.dispose();
    super.dispose();
  }

  Future<void> _loadStore({bool reset = false}) async {
    if (_loading) return;
    setState(() { _loading = true; _tip = null; });
    final p = reset ? 1 : _page + 1;
    final kw = _search.text.trim();
    try {
      final qs = StringBuffer('?page=$p&size=20');
      if (kw.isNotEmpty) qs.write('&keyword=${Uri.encodeComponent(kw)}');
      if (_subject != null) qs.write('&subject=$_subject');
      if (_grade != null) qs.write('&grade=$_grade');
      final d = await Api.get('/api/bank/store/items$qs');
      setState(() {
        _page = p;
        _total = (d['total'] as num?)?.toInt() ?? 0;
        _items = reset
            ? (d['list'] as List).cast<Map<String, dynamic>>()
            : [..._items, ...(d['list'] as List).cast<Map<String, dynamic>>()];
      });
    } on ApiException catch (e) {
      setState(() => _tip = e.message);
    } finally {
      setState(() => _loading = false);
    }
  }

  Future<void> _loadMine() async {
    try {
      final d = await Api.get('/api/bank/store/mine?page=1&size=100');
      setState(() => _mine = (d['list'] as List).cast<Map<String, dynamic>>());
    } on ApiException catch (e) {
      setState(() => _tip = e.message);
    }
  }

  Future<void> _add(Map<String, dynamic> item) async {
    try {
      final d = await Api.post('/api/bank/store/items/${item['id']}/add');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(
          content: Text(d['added'] == true ? '已添加到我的题库' : '该题库已在我的题库中'),
        ));
      }
      await _loadStore(reset: true);
      await _loadMine();
    } on ApiException catch (e) {
      setState(() => _tip = e.message);
    }
  }

  Future<void> _remove(Map<String, dynamic> item) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('移除题库'),
        content: Text('「${item['title']}」的题目将进入回收站，子女分配关系一并作废。确定移除？'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('移除')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await Api.delete('/api/bank/store/mine/${item['storeId']}');
      await _loadMine();
      await _loadStore(reset: true);
    } on ApiException catch (e) {
      setState(() => _tip = e.message);
    }
  }

  Future<void> _assign(Map<String, dynamic> item) async {
    try {
      final profiles = ((await Api.get('/api/bank/store/profiles'))['list'] as List).cast<Map<String, dynamic>>();
      if (!mounted) return;
      final assigned = (item['assignedProfileIds'] as List).cast<num>().map((n) => n.toInt()).toSet();
      final selected = {...assigned};
      await showModalBottomSheet<void>(
        context: context,
        shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(24))),
        builder: (_) => StatefulBuilder(
          builder: (ctx, setSheet) => Padding(
            padding: const EdgeInsets.all(24),
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('分配子女 · ${item['title']}', style: Ui.title),
              const SizedBox(height: 12),
              if (profiles.isEmpty) const Text('暂无子女档案，请先在学生档案中创建', style: Ui.caption),
              ...profiles.map((p) {
                final id = (p['id'] as num).toInt();
                return CheckboxListTile(
                  value: selected.contains(id),
                  onChanged: (v) => setSheet(() => v == true ? selected.add(id) : selected.remove(id)),
                  title: Text('${p['nickname']}（${p['grade']}年级）', style: Ui.body),
                  contentPadding: EdgeInsets.zero,
                );
              }),
              const SizedBox(height: 8),
              UiButton(
                label: '保存分配', loading: false,
                onPressed: profiles.isEmpty ? null : () async {
                  try {
                    final toAdd = selected.difference(assigned).toList();
                    final toDel = assigned.difference(selected).toList();
                    if (toAdd.isNotEmpty) {
                      await Api.post('/api/bank/store/mine/${item['storeId']}/assign', body: {'profileIds': toAdd});
                    }
                    for (final pid in toDel) {
                      await Api.delete('/api/bank/store/mine/${item['storeId']}/assign/$pid');
                    }
                    if (ctx.mounted) Navigator.pop(ctx);
                    await _loadMine();
                  } on ApiException catch (e) {
                    if (ctx.mounted) ScaffoldMessenger.of(ctx).showSnackBar(SnackBar(content: Text(e.message)));
                  }
                },
              ),
            ]),
          ),
        ),
      );
    } on ApiException catch (e) {
      setState(() => _tip = e.message);
    }
  }

  Future<void> _detail(Map<String, dynamic> item) async {
    try {
      final d = await Api.get('/api/bank/store/items/${item['id']}');
      if (!mounted) return;
      final questions = (d['questions'] as List?)?.cast<Map<String, dynamic>>() ?? [];
      await showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(24))),
        builder: (_) => SizedBox(
          height: MediaQuery.of(context).size.height * 0.75,
          child: Column(children: [
            Padding(padding: const EdgeInsets.all(20), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(d['title']?.toString() ?? '', style: Ui.title),
              const SizedBox(height: 6),
              Text('${d['description'] ?? ''}　${_subjects[(d['subject'] as num? ?? 1).toInt() - 1]} · ${d['grade']}年级 · ${d['questionCount']}题 · ${d['downloadCount']}次下载', style: Ui.caption),
            ])),
            const Divider(height: 1),
            Expanded(child: ListView.separated(
              padding: const EdgeInsets.all(20),
              itemCount: questions.length,
              separatorBuilder: (_, _) => const SizedBox(height: 12),
              itemBuilder: (_, i) {
                final q = questions[i];
                return Container(
                  padding: const EdgeInsets.all(14),
                  decoration: BoxDecoration(color: Ui.field, borderRadius: BorderRadius.circular(12)),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text('${i + 1}. ${q['stem']}', style: Ui.body),
                    if (q['answer']?.toString().isNotEmpty == true) ...[
                      const SizedBox(height: 6),
                      Text('答案：${q['answer']}', style: const TextStyle(fontSize: 13, color: Ui.success)),
                    ],
                    if (q['analysis']?.toString().isNotEmpty == true)
                      Text('解析：${q['analysis']}', style: Ui.caption),
                  ]),
                );
              },
            )),
          ]),
        ),
      );
    } on ApiException catch (e) {
      setState(() => _tip = e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Ui.bg,
      appBar: uiAppBar(
        title: '题库商店',
        leading: IconButton(icon: const Icon(Icons.arrow_back_rounded), onPressed: () => Navigator.pop(context)),
        bottom: TabBar(
          controller: _tab,
          labelColor: Ui.primary, unselectedLabelColor: Ui.muted, indicatorColor: Ui.primary,
          onTap: (i) { if (i == 1) _loadMine(); },
          tabs: const [Tab(text: '商店'), Tab(text: '我的题库')],
        ),
      ),
      body: TabBarView(controller: _tab, children: [_storeTab(), _mineTab()]),
    );
  }

  // ---------- Tab 1 商店 ----------
  Widget _storeTab() {
    return Column(children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
        child: UiInput(
          controller: _search, hint: '搜索题库名称/简介', icon: Icons.search_rounded,
          keyboardType: TextInputType.text, textInputAction: TextInputAction.search,
        ),
      ),
      SizedBox(height: 44, child: ListView(
        scrollDirection: Axis.horizontal, padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        children: [
          _chip('全部科目', null),
          for (var i = 0; i < _subjects.length; i++) _chip(_subjects[i], i + 1),
          ...List.generate(12, (i) => _chip('${i + 1}年级', null, grade: i + 1)),
        ],
      )),
      Expanded(child: RefreshIndicator(
        onRefresh: () async { await _loadStore(reset: true); await _loadMine(); },
        child: ListView.builder(
          controller: _scroll, padding: const EdgeInsets.all(20),
          itemCount: _items.length + 2,
          itemBuilder: (_, i) {
            if (i == 0 && _tip != null) return Padding(padding: const EdgeInsets.only(bottom: 16), child: UiBanner(message: _tip!, tone: UiTone.error));
            if (_items.isEmpty && !_loading) return const Padding(padding: EdgeInsets.only(top: 60), child: Center(child: Text('暂无已上架题库', style: Ui.caption)));
            if (i >= _items.length + 1 - (_items.length < _total ? 1 : 0)) {
              return const Padding(padding: EdgeInsets.all(16), child: Center(child: CircularProgressIndicator(strokeWidth: 2)));
            }
            final item = _items[i];
            return _storeCard(item);
          },
        ),
      )),
    ]);
  }

  Widget _chip(String label, int? subject, {int? grade}) {
    final selected = grade != null ? _grade == grade : (_grade == null && _subject == subject);
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: ChoiceChip(
        label: Text(label, style: const TextStyle(fontSize: 13)),
        selected: selected, selectedColor: Ui.primary,
        labelStyle: TextStyle(color: selected ? Colors.white : Ui.label, fontSize: 13, fontWeight: FontWeight.w600),
        backgroundColor: Ui.surface, side: const BorderSide(color: Ui.fieldBorder),
        onSelected: (_) => setState(() {
          if (grade != null) {
            _grade = _grade == grade ? null : grade;
          } else {
            _subject = _subject == subject ? null : subject;
          }
          _loadStore(reset: true);
        }),
      ),
    );
  }

  Widget _storeCard(Map<String, dynamic> item) {
    final added = item['added'] == true;
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: UiCard(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Expanded(child: Text(item['title']?.toString() ?? '', style: Ui.title)),
          if (added) Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(color: Ui.successBg, borderRadius: BorderRadius.circular(8)),
            child: const Text('已添加', style: TextStyle(fontSize: 12, color: Ui.success, fontWeight: FontWeight.w600)),
          ),
        ]),
        if (item['description']?.toString().isNotEmpty == true) ...[
          const SizedBox(height: 6),
          Text(item['description'].toString(), style: Ui.caption, maxLines: 2, overflow: TextOverflow.ellipsis),
        ],
        const SizedBox(height: 10),
        Wrap(spacing: 6, runSpacing: 6, children: [
          _meta('${_subjects[(item['subject'] as num? ?? 1).toInt() - 1]} · ${item['grade']}年级'),
          _meta('${item['questionCount']}题'),
          _meta('${item['downloadCount']}次下载'),
          ...((item['tags'] as List?)?.cast<Map<String, dynamic>>() ?? []).map((t) => Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
            decoration: BoxDecoration(
              color: (_tagColorMap[t['color']] ?? Ui.primary).withValues(alpha: 0.1),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Text(t['name'].toString(), style: TextStyle(fontSize: 12, color: _tagColorMap[t['color']] ?? Ui.primary, fontWeight: FontWeight.w600)),
          )),
        ]),
        const SizedBox(height: 14),
        Row(children: [
          Expanded(child: UiButton(label: '查看题目', primary: false, icon: Icons.menu_book_rounded,
              onPressed: () => _detail(item))),
          const SizedBox(width: 10),
          Expanded(child: UiButton(
            label: added ? '已添加' : '添加',
            onPressed: added ? () => _assign(item) : () => _add(item),
          )),
        ]),
      ])),
    );
  }

  Widget _meta(String text) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
    decoration: BoxDecoration(color: Ui.field, borderRadius: BorderRadius.circular(8)),
    child: Text(text, style: const TextStyle(fontSize: 12, color: Ui.label)),
  );

  // ---------- Tab 2 我的题库 ----------
  Widget _mineTab() {
    return RefreshIndicator(
      onRefresh: _loadMine,
      child: ListView(padding: const EdgeInsets.all(20), children: [
        if (_tip != null) ...[UiBanner(message: _tip!, tone: UiTone.error), const SizedBox(height: 16)],
        if (_mine.isEmpty && _tip == null)
          const Padding(padding: EdgeInsets.only(top: 60), child: Center(child: Text('还没有添加题库，去商店逛逛吧', style: Ui.caption))),
        ..._mine.map(_mineCard),
        const SizedBox(height: 24),
      ]),
    );
  }

  Widget _mineCard(Map<String, dynamic> item) {
    final assignedIds = (item['assignedProfileIds'] as List).cast<num>().map((n) => n.toInt()).toList();
    return Padding(
      padding: const EdgeInsets.only(bottom: 16),
      child: UiCard(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Row(children: [
          Expanded(child: Text(item['title']?.toString() ?? '', style: Ui.title)),
          IconButton(icon: const Icon(Icons.delete_outline_rounded, color: Ui.error), tooltip: '移除',
              onPressed: () => _remove(item)),
        ]),
        Text('${_subjects[(item['subject'] as num? ?? 1).toInt() - 1]} · ${item['grade']}年级 · ${item['questionCount']}题 · 添加于 ${item['addedAt']}', style: Ui.caption),
        const SizedBox(height: 10),
        Wrap(spacing: 6, runSpacing: 6, children: [
          _meta('下载量 ${item['downloadCount']}'),
          ...((item['tags'] as List?)?.cast<String>() ?? []).map((t) => _meta('#$t')),
        ]),
        const SizedBox(height: 10),
        Text('分配对象：${assignedIds.isEmpty ? '未分配' : '已分配 ${assignedIds.length} 名子女'}', style: Ui.caption),
        const SizedBox(height: 12),
        UiButton(label: '分配子女', icon: Icons.family_restroom_rounded, width: double.infinity,
            onPressed: () => _assign(item)),
      ])),
    );
  }
}
