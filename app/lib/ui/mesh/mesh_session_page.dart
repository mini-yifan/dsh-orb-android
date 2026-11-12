import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../state/mesh_state.dart';
import '../chat/render/markdown_body.dart';
import '../chat/widgets/user_bubble.dart';
import '../theme/app_icons.dart';

/// One remote task session: durable DSH events in, Markdown/tool cards out.
/// The phone reuses the app's Markdown renderer instead of shipping a second
/// transcript engine.
class MeshSessionPage extends ConsumerStatefulWidget {
  const MeshSessionPage({
    super.key,
    required this.nodeId,
    required this.sessionId,
    required this.title,
    required this.nodeName,
    required this.workspaceName,
    required this.controllable,
  });

  static Route<void> route({
    required String nodeId,
    required String sessionId,
    required String title,
    required String nodeName,
    required String workspaceName,
    required bool controllable,
  }) => MaterialPageRoute<void>(
    builder: (_) => MeshSessionPage(
      nodeId: nodeId,
      sessionId: sessionId,
      title: title,
      nodeName: nodeName,
      workspaceName: workspaceName,
      controllable: controllable,
    ),
  );

  final String nodeId;
  final String sessionId;
  final String title;
  final String nodeName;
  final String workspaceName;
  final bool controllable;

  @override
  ConsumerState<MeshSessionPage> createState() => _MeshSessionPageState();
}

class _MeshSessionPageState extends ConsumerState<MeshSessionPage> {
  final _input = TextEditingController();
  final _scroll = ScrollController();
  List<MeshEvent> _events = const [];
  int _cursor = 0;
  int _generation = 0;
  bool _loading = true;
  bool _sending = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    unawaited(_reload());
  }

  @override
  void dispose() {
    _generation += 1;
    _input.dispose();
    _scroll.dispose();
    super.dispose();
  }

  bool get _running {
    var running = false;
    for (final event in _events) {
      if (event.type == 'turn/start') running = true;
      if (event.type == 'turn/end') running = false;
    }
    return running;
  }

  bool get _atBottom => !_scroll.hasClients || _scroll.position.maxScrollExtent - _scroll.position.pixels < 120;

  String _errorText(Object error) {
    final l10n = context.l10n;
    if (error is MeshApiException) {
      return error.message.isEmpty ? l10n.meshRequestFailed : error.message;
    }
    return error.toString();
  }

  Future<void> _reload() async {
    _generation += 1;
    final generation = _generation;
    final api = ref.read(meshApiProvider);
    if (api == null) {
      if (mounted) {
        setState(() {
          _loading = false;
          _error = context.l10n.meshEngineOff;
        });
      }
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final frames = await api.page(widget.nodeId, widget.sessionId);
      if (!mounted || generation != _generation) return;
      setState(() {
        _events = frames.events;
        _cursor = frames.cursor;
        _loading = false;
      });
      _follow();
      unawaited(_live(generation));
    } catch (error) {
      if (!mounted || generation != _generation) return;
      setState(() {
        _loading = false;
        _error = _errorText(error);
      });
    }
  }

  Future<void> _live(int generation) async {
    while (mounted && generation == _generation) {
      final api = ref.read(meshApiProvider);
      if (api == null) {
        if (mounted) setState(() => _error = context.l10n.meshEngineOff);
        return;
      }
      try {
        final frames = await api.stream(widget.nodeId, widget.sessionId, _cursor);
        if (!mounted || generation != _generation) return;
        if (frames.events.isNotEmpty) {
          final follow = _atBottom;
          setState(() {
            _events = _merge(_events, frames.events);
            _cursor = frames.cursor;
            _error = null;
          });
          if (follow) _follow();
        } else if (frames.cursor > _cursor) {
          _cursor = frames.cursor;
        }
      } catch (error) {
        if (!mounted || generation != _generation) return;
        if (error is MeshApiException && (error.statusCode == 401 || error.statusCode == 403)) {
          setState(() => _error = error.message);
          return;
        }
        // The local engine may be restarting; keep the session and retry.
        await Future<void>.delayed(const Duration(seconds: 1));
      }
    }
  }

  List<MeshEvent> _merge(List<MeshEvent> current, List<MeshEvent> incoming) {
    final bySeq = <int, MeshEvent>{
      for (final event in current) event.seq: event,
      for (final event in incoming) event.seq: event,
    };
    final merged = bySeq.values.toList()..sort((left, right) => left.seq.compareTo(right.seq));
    return merged.length > 500 ? merged.sublist(merged.length - 500) : merged;
  }

  void _follow() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scroll.hasClients) return;
      _scroll.animateTo(
        _scroll.position.maxScrollExtent,
        duration: const Duration(milliseconds: 180),
        curve: Curves.easeOut,
      );
    });
  }

  Future<void> _send() async {
    if (!widget.controllable || _sending) return;
    final text = _input.text.trim();
    if (text.isEmpty) return;
    final api = ref.read(meshApiProvider);
    if (api == null) {
      setState(() => _error = context.l10n.meshEngineOff);
      return;
    }
    setState(() {
      _sending = true;
      _error = null;
    });
    try {
      await api.prompt(widget.nodeId, widget.sessionId, text);
      if (!mounted) return;
      _input.clear();
    } catch (error) {
      if (mounted) setState(() => _error = _errorText(error));
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  Future<void> _stop() async {
    final api = ref.read(meshApiProvider);
    if (api == null) return;
    try {
      await api.cancel(widget.nodeId, widget.sessionId);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(context.l10n.meshStopped)));
    } catch (error) {
      if (mounted) setState(() => _error = _errorText(error));
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final rows = reduceMeshEvents(_events);
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(widget.title, maxLines: 1, overflow: TextOverflow.ellipsis),
            Text(
              '${widget.nodeName} · ${widget.workspaceName}',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodySmall,
            ),
          ],
        ),
        actions: [
          IconButton(
            onPressed: () => unawaited(_reload()),
            tooltip: l10n.meshRefresh,
            icon: const Icon(AppIcons.refresh),
          ),
        ],
      ),
      body: SafeArea(
        top: false,
        child: Column(
          children: [
            if (_error != null) _errorBanner(theme, _error!),
            Expanded(
              child: _loading
                  ? const Center(child: CircularProgressIndicator())
                  : rows.isEmpty
                  ? Center(child: Text(l10n.meshEmptySession))
                  : ListView.builder(
                      controller: _scroll,
                      padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
                      itemCount: rows.length,
                      itemBuilder: (context, index) => _row(l10n, theme, rows[index]),
                    ),
            ),
            if (widget.controllable) _composer(l10n, theme) else _readOnly(l10n),
          ],
        ),
      ),
    );
  }

  Widget _row(L10n l10n, ThemeData theme, MeshRow row) {
    switch (row.kind) {
      case MeshRowKind.user:
        return Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: Align(alignment: Alignment.centerRight, child: UserBubble(text: row.text)),
        );
      case MeshRowKind.assistant:
        return Padding(
          padding: const EdgeInsets.only(bottom: 10),
          child: MarkdownBody(text: row.text, repairBareViews: true),
        );
      case MeshRowKind.reasoning:
        return Card(
          margin: const EdgeInsets.only(bottom: 10),
          child: ExpansionTile(
            title: Text(l10n.meshReasoning),
            tilePadding: const EdgeInsets.symmetric(horizontal: 12),
            childrenPadding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
            children: [
              MarkdownBody(text: row.text, secondary: true),
            ],
          ),
        );
      case MeshRowKind.tool:
        return _toolCard(l10n, theme, row);
    }
  }

  Widget _toolCard(L10n l10n, ThemeData theme, MeshRow row) => Card(
    margin: const EdgeInsets.only(bottom: 10),
    child: ExpansionTile(
      title: Text(
        '${l10n.meshTool} · ${row.toolName}',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      tilePadding: const EdgeInsets.symmetric(horizontal: 12),
      childrenPadding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
      children: [
        if (row.args.isNotEmpty) _code(l10n.meshTool, row.args),
        if (row.result.isNotEmpty) _code(l10n.meshResult, row.result, error: row.isError),
      ],
    ),
  );

  Widget _code(String label, String text, {bool error = false}) {
    final theme = Theme.of(context);
    final color = error ? theme.colorScheme.error : theme.colorScheme.onSurfaceVariant;
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: theme.textTheme.labelSmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          const SizedBox(height: 4),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: theme.colorScheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(10),
            ),
            child: SelectableText(
              text,
              style: theme.textTheme.bodySmall?.copyWith(fontFamily: 'monospace', color: color, height: 1.45),
            ),
          ),
        ],
      ),
    );
  }

  Widget _errorBanner(ThemeData theme, String text) => Material(
    color: theme.colorScheme.errorContainer,
    child: Padding(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(AppIcons.error, size: 18, color: theme.colorScheme.onErrorContainer),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              text,
              style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onErrorContainer),
            ),
          ),
        ],
      ),
    ),
  );

  Widget _readOnly(L10n l10n) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 10, 16, 16),
    child: Text(
      l10n.meshReadOnlyHint,
      style: Theme.of(context).textTheme.bodySmall?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
    ),
  );

  Widget _composer(L10n l10n, ThemeData theme) => Padding(
    padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        Expanded(
          child: TextField(
            controller: _input,
            minLines: 1,
            maxLines: 4,
            enabled: !_sending,
            textInputAction: TextInputAction.newline,
            decoration: InputDecoration(
              hintText: l10n.meshSendHint,
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(14)),
              contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
            ),
          ),
        ),
        const SizedBox(width: 8),
        IconButton.filled(
          onPressed: _sending ? null : () => unawaited(_send()),
          tooltip: l10n.commonSend,
          icon: _sending
              ? const SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2))
              : const Icon(AppIcons.send),
        ),
        if (_running) ...[
          const SizedBox(width: 6),
          IconButton.filledTonal(
            onPressed: () => unawaited(_stop()),
            tooltip: l10n.commonStop,
            icon: const Icon(AppIcons.stopGenerating),
          ),
        ],
      ],
    ),
  );
}