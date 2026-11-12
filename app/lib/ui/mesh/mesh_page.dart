import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../state/mesh_state.dart';
import '../common/overview_card.dart';
import '../common/status_dot.dart';
import '../remote/pairing_page.dart' show pairingScannerProvider;
import '../settings/settings_widgets.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import 'mesh_session_page.dart';
import 'mesh_ui.dart';

/// 互联: this phone's view of the Mesh. It talks to the local engine's
/// `/.dsh-mesh/v1` routes with the same authenticated [DshConnection] as the
/// rest of the app; the plugin does the peer work.
class MeshPage extends ConsumerStatefulWidget {
  const MeshPage({super.key});

  static Route<void> route() => MaterialPageRoute<void>(builder: (_) => const MeshPage());

  @override
  ConsumerState<MeshPage> createState() => _MeshPageState();
}

class _MeshPageState extends ConsumerState<MeshPage> {
  final _pairing = TextEditingController();
  final _expanded = <String>{};
  MeshStatus? _status;
  MeshTree? _tree;
  bool _loading = true;
  bool _pairingBusy = false;
  String? _error;
  Timer? _poll;
  int _load = 0;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
    _poll = Timer.periodic(const Duration(seconds: 5), (_) {
      if (mounted) unawaited(_refresh(quiet: true));
    });
  }

  @override
  void dispose() {
    _poll?.cancel();
    _pairing.dispose();
    super.dispose();
  }

  Future<void> _refresh({bool quiet = false}) async {
    final api = ref.read(meshApiProvider);
    final token = ++_load;
    if (api == null) {
      if (mounted) {
        setState(() {
          _loading = false;
          _status = null;
          _tree = null;
          _error = null;
        });
      }
      return;
    }
    if (!quiet && mounted) {
      setState(() {
        _loading = true;
        _error = null;
      });
    }
    try {
      final status = await api.status();
      final tree = await api.tree();
      if (!mounted || token != _load) return;
      setState(() {
        _status = status;
        _tree = tree;
        _loading = false;
        _error = null;
      });
    } catch (error) {
      if (!mounted || token != _load) return;
      setState(() {
        _loading = false;
        _error = _errorText(error);
      });
    }
  }

  String _errorText(Object error) {
    final l10n = context.l10n;
    if (error is MeshApiException) {
      return error.message.isEmpty ? l10n.meshRequestFailed : error.message;
    }
    return error.toString();
  }

  Future<void> _pair([String? value]) async {
    final text = (value ?? _pairing.text).trim();
    if (text.isEmpty) {
      setState(() => _error = context.l10n.meshPairingEmpty);
      return;
    }
    final api = ref.read(meshApiProvider);
    if (api == null) {
      setState(() => _error = context.l10n.meshEngineOff);
      return;
    }
    setState(() {
      _pairingBusy = true;
      _error = null;
    });
    try {
      await api.pair(text);
      if (!mounted) return;
      _pairing.clear();
      await _refresh();
    } catch (error) {
      if (mounted) setState(() => _error = _errorText(error));
    } finally {
      if (mounted) setState(() => _pairingBusy = false);
    }
  }

  Future<void> _scan() async {
    final code = await Navigator.of(context).push<String>(
      MaterialPageRoute<String>(builder: (_) => const _MeshScanPage()),
    );
    if (!mounted || code == null || code.trim().isEmpty) return;
    await _pair(code);
  }

  Future<void> _unpair() async {
    final l10n = context.l10n;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l10n.meshLeave),
        content: Text(l10n.meshLeaveConfirm),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: Text(l10n.commonCancel)),
          FilledButton(onPressed: () => Navigator.of(context).pop(true), child: Text(l10n.meshLeave)),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    final api = ref.read(meshApiProvider);
    if (api == null) return;
    try {
      await api.unpair();
      await _refresh();
    } catch (error) {
      if (mounted) setState(() => _error = _errorText(error));
    }
  }

  Future<void> _rename() async {
    final name = await showDialog<String>(
      context: context,
      builder: (_) => _RenameDialog(initial: _status?.node?.name ?? ''),
    );
    if (name == null || !mounted) return;
    if (name.trim().isEmpty) {
      setState(() => _error = context.l10n.meshNameEmpty);
      return;
    }
    final api = ref.read(meshApiProvider);
    if (api == null) return;
    try {
      await api.rename(name.trim());
      await _refresh();
    } catch (error) {
      if (mounted) setState(() => _error = _errorText(error));
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final api = ref.watch(meshApiProvider);
    final status = _status;
    return AppPage.list(
      title: l10n.meshTitle,
      subtitle: Text(l10n.meshSubtitle),
      actions: [
        IconButton(
          onPressed: () => unawaited(_refresh()),
          tooltip: l10n.meshRefresh,
          icon: const Icon(AppIcons.refresh),
        ),
      ],
      children: [
        if (api == null)
          _messageCard(l10n.meshEngineOff)
        else if (status == null && _loading)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 40),
            child: Center(child: CircularProgressIndicator()),
          )
        else if (status?.paired != true) ...[
          if (_error != null) _errorCard(_error!),
          _joinCard(l10n),
        ] else ...[
          if (_error != null) _errorCard(_error!),
          _header(l10n, status!),
          _treeSection(l10n, _tree),
          _settings(l10n),
        ],
      ],
    );
  }

  Widget _messageCard(String text) => SettingsSection(
    children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 16),
        child: Text(text, style: Theme.of(context).textTheme.bodyMedium),
      ),
    ],
  );

  Widget _errorCard(String text) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
    child: Material(
      color: Theme.of(context).colorScheme.errorContainer,
      borderRadius: BorderRadius.circular(14),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(AppIcons.error, size: 18, color: Theme.of(context).colorScheme.onErrorContainer),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                text,
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: Theme.of(context).colorScheme.onErrorContainer),
              ),
            ),
          ],
        ),
      ),
    ),
  );

  Widget _joinCard(L10n l10n) => SettingsSection(
    title: l10n.meshJoinTitle,
    footer: Text(l10n.meshJoinHint),
    children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 14, 16, 8),
        child: TextField(
          controller: _pairing,
          minLines: 1,
          maxLines: 3,
          enabled: !_pairingBusy,
          keyboardType: TextInputType.visiblePassword,
          decoration: InputDecoration(
            labelText: l10n.meshPairingLabel,
            hintText: l10n.meshJoinHint,
            border: const OutlineInputBorder(),
          ),
        ),
      ),
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
        child: Row(
          children: [
            Expanded(
              child: FilledButton(
                onPressed: _pairingBusy ? null : () => unawaited(_pair()),
                child: _pairingBusy
                    ? const SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2))
                    : Text(l10n.meshConnect),
              ),
            ),
            const SizedBox(width: 12),
            OutlinedButton.icon(
              onPressed: _pairingBusy ? null : () => unawaited(_scan()),
              icon: const Icon(AppIcons.qrCode, size: 18),
              label: Text(l10n.meshScan),
            ),
          ],
        ),
      ),
    ],
  );

  Widget _header(L10n l10n, MeshStatus status) => OverviewCard(
    leading: const OverviewGlyph(AppIcons.remoteHost, tint: 4),
    title: Text(status.node?.name ?? l10n.meshThisPhone),
    subtitle: Text('${status.serverUrl} · ${l10n.meshPeers(status.peersOnline, status.peersTotal)}'),
    footer: OverviewFooter(
      tone: status.connected ? StatusTone.ok : StatusTone.warn,
      text: status.connected ? l10n.meshConnected : l10n.meshOffline,
    ),
    onTap: () => unawaited(_refresh()),
    margin: const EdgeInsets.fromLTRB(16, 0, 16, 8),
  );

  Widget _treeSection(L10n l10n, MeshTree? tree) {
    final peers = [...?tree?.peers];
    if (peers.isEmpty) {
      return SettingsSection(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 16),
            child: Text(l10n.meshNoPeers, style: Theme.of(context).textTheme.bodyMedium),
          ),
        ],
      );
    }
    peers.sort((left, right) {
      if (left.online != right.online) return left.online ? -1 : 1;
      return left.name.toLowerCase().compareTo(right.name.toLowerCase());
    });
    return Column(
      children: [
        for (final peer in peers) _peerSection(l10n, peer),
      ],
    );
  }

  Widget _peerSection(L10n l10n, MeshNode peer) {
    final workspaces = [...peer.workspaces]
      ..sort((left, right) {
        final latest = right.latestAt.compareTo(left.latestAt);
        return latest != 0 ? latest : left.name.compareTo(right.name);
      });
    final children = <Widget>[];
    if (workspaces.isEmpty) {
      children.add(
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 16, 16, 16),
          child: Text(l10n.meshNoSessions),
        ),
      );
    }
    for (final workspace in workspaces) {
      children.add(
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 14, 16, 6),
          child: Row(
            children: [
              const Icon(AppIcons.folder, size: 18),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  workspace.name,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: Theme.of(context).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w600),
                ),
              ),
            ],
          ),
        ),
      );
      final sessions = [...workspace.sessions]..sort((left, right) => right.updatedAt.compareTo(left.updatedAt));
      if (sessions.isEmpty) {
        children.add(Padding(padding: const EdgeInsets.fromLTRB(16, 4, 16, 12), child: Text(l10n.meshNoSessions)));
        continue;
      }
      final key = '${peer.nodeId}/${workspace.workspaceId}';
      final expanded = _expanded.contains(key);
      final shown = expanded ? sessions : sessions.take(5).toList();
      for (final session in shown) {
        children.add(_sessionTile(l10n, peer, workspace, session));
      }
      final hidden = sessions.length - shown.length;
      if (hidden > 0) {
        children.add(
          SettingsTile(
            title: l10n.meshShowMore(hidden),
            onTap: () => setState(() => _expanded.add(key)),
          ),
        );
      } else if (expanded && sessions.length > 5) {
        children.add(
          SettingsTile(
            title: l10n.meshShowLess,
            onTap: () => setState(() => _expanded.remove(key)),
          ),
        );
      }
    }
    return SettingsSection(
      title: '${peer.name} · ${peer.online ? l10n.meshOnline : l10n.meshOffline}',
      children: children,
    );
  }

  Widget _sessionTile(L10n l10n, MeshNode peer, MeshWorkspace workspace, MeshSession session) {
    final details = <String>[meshStatusLabel(l10n, session.status)];
    if (session.status == 'idle') {
      final updated = meshUpdatedLabel(context, session.updatedAt);
      if (updated.isNotEmpty) details.add(updated);
    }
    if (!session.controllable) details.add(l10n.meshReadOnly);
    return SettingsTile(
      icon: session.controllable ? AppIcons.chat : AppIcons.lock,
      title: session.title,
      subtitle: details.join(' · '),
      trailing: const Icon(AppIcons.disclosure, size: 16),
      onTap: () => Navigator.of(context).push(
        MeshSessionPage.route(
          nodeId: peer.nodeId,
          sessionId: session.sessionId,
          title: session.title,
          nodeName: peer.name,
          workspaceName: workspace.name,
          controllable: session.controllable,
        ),
      ),
    );
  }

  Widget _settings(L10n l10n) => SettingsSection(
    children: [
      SettingsTile(icon: AppIcons.edit, title: l10n.meshRename, onTap: () => unawaited(_rename())),
      SettingsTile(
        icon: AppIcons.logout,
        title: l10n.meshLeave,
        danger: true,
        onTap: () => unawaited(_unpair()),
      ),
    ],
  );
}

class _RenameDialog extends StatefulWidget {
  const _RenameDialog({required this.initial});

  final String initial;

  @override
  State<_RenameDialog> createState() => _RenameDialogState();
}

class _RenameDialogState extends State<_RenameDialog> {
  late final TextEditingController _controller;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: widget.initial);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    return AlertDialog(
      title: Text(l10n.meshRename),
      content: TextField(
        controller: _controller,
        autofocus: true,
        maxLength: 80,
        decoration: InputDecoration(labelText: l10n.meshRenameHint, border: const OutlineInputBorder()),
        onSubmitted: (value) => Navigator.of(context).pop(value.trim()),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: Text(l10n.commonCancel)),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(_controller.text.trim()),
          child: Text(l10n.commonSave),
        ),
      ],
    );
  }
}

class _MeshScanPage extends ConsumerStatefulWidget {
  const _MeshScanPage();

  @override
  ConsumerState<_MeshScanPage> createState() => _MeshScanPageState();
}

class _MeshScanPageState extends ConsumerState<_MeshScanPage> {
  bool _done = false;

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    return Scaffold(
      appBar: AppBar(title: Text(l10n.meshScanTitle)),
      body: ref.watch(pairingScannerProvider)(
        context,
        (code) {
          if (_done || !mounted) return;
          _done = true;
          Navigator.of(context).pop(code);
        },
      ),
    );
  }
}