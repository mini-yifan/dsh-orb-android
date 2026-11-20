import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../l10n/l10n.dart';
import '../../state/mesh_state.dart';
import '../common/overview_card.dart';
import '../common/status_dot.dart';
import '../settings/settings_widgets.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import 'mesh_session_page.dart';
import 'mesh_ui.dart';

/// 互联: this phone's view of the Mesh. It talks to the local engine's
/// `/.dsh-mesh/v1` routes with the same authenticated [DshConnection] as the
/// rest of the app; the plugin does the peer work.
///
/// Joining follows the desktop settings page: flip the switch, the system
/// browser opens the account server, the user approves this phone there, and
/// this page polls until the plugin has the credentials.
class MeshPage extends ConsumerStatefulWidget {
  const MeshPage({super.key});

  static Route<void> route() => MaterialPageRoute<void>(builder: (_) => const MeshPage());

  @override
  ConsumerState<MeshPage> createState() => _MeshPageState();
}

class _MeshPageState extends ConsumerState<MeshPage> {
  /// How often the page re-checks a link the browser is holding.
  static const _linkPollInterval = Duration(seconds: 2);

  /// Built-in default Mesh server for DSH Orb; users running a self-hosted
  /// server can replace it in the address field at any time.
  static const _fallbackDefaultServer = 'https://dshorb.top';

  String _resolvedDefaultServer([MeshStatus? status]) {
    final fromPlugin = (status ?? _status)?.defaultServer.trim() ?? '';
    return fromPlugin.isNotEmpty ? fromPlugin : _fallbackDefaultServer;
  }

  final _server = TextEditingController();
  final _expanded = <String>{};
  String? _expandedPeerId;
  MeshStatus? _status;
  MeshTree? _tree;
  bool _loading = true;
  bool _pairingBusy = false;
  String? _error;
  Timer? _poll;
  Timer? _linkPoll;
  int _load = 0;

  /// The browser hand-off in flight, if any.
  MeshLinkStart? _link;
  bool _serverTouched = false;

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
    _linkPoll?.cancel();
    _server.dispose();
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
      final tree = status.paired ? await api.tree() : const MeshTree(nodes: [], cursor: 0);
      if (!mounted || token != _load) return;
      setState(() {
        _status = status;
        _tree = tree;
        _loading = false;
        // A quiet poll must not wipe the message from the last action: the
        // user would see their failed tap as "nothing happened".
        if (!quiet) _error = null;
        // Offer the deployment's server until the user types their own, but
        // never overwrite an address they are already editing.
        final fallback = _resolvedDefaultServer(status);
        if (!_serverTouched && _server.text.trim().isEmpty && fallback.isNotEmpty) {
          _server.text = fallback;
        }
      });
    } catch (error) {
      if (!mounted || token != _load) return;
      setState(() {
        _loading = false;
        // A background poll failing is not the user's action; keep quiet about
        // it while the page already shows what they did.
        if (!quiet || _status == null) _error = _errorText(error);
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

  /// The address the switch links against: what the user typed, or the default
  /// server when the field is untouched.
  String get _targetServer {
    final typed = _server.text.trim();
    return typed.isEmpty ? _resolvedDefaultServer() : typed;
  }

  /// Flip the switch on: create the link, open the browser, then poll for the
  /// approval. Nothing here blocks on the browser.
  Future<void> _join() async {
    final api = ref.read(meshApiProvider);
    if (api == null) {
      setState(() => _error = context.l10n.meshEngineOff);
      return;
    }
    final target = _targetServer;
    if (target.isEmpty) {
      setState(() => _error = context.l10n.meshServerRequired);
      return;
    }
    setState(() {
      _pairingBusy = true;
      _error = null;
    });
    try {
      final started = await api.startLink(target);
      if (!mounted) return;
      if (started.approvalUrl.isEmpty) {
        throw const MeshApiException(0, 'link_failed', '');
      }
      setState(() => _link = started);
      final opened = await _launchApprovalUrl(started.approvalUrl);
      if (!mounted) return;
      if (!opened) {
        // The card keeps the URL visible so it can be copied with one tap.
        setState(() => _error = context.l10n.meshLinkOpenFailed);
      }
      _startLinkPolling();
    } catch (error) {
      if (mounted) {
        setState(() {
          _link = null;
          _error = _errorText(error);
        });
      }
    } finally {
      if (mounted) setState(() => _pairingBusy = false);
    }
  }

  /// Ask the browser approval question every couple of seconds. Polling beats
  /// one long request here: Android suspends the app while the browser is in
  /// front, and the plugin keeps the pending link across that gap.
  void _startLinkPolling() {
    _linkPoll?.cancel();
    _linkPoll = Timer.periodic(_linkPollInterval, (_) => unawaited(_pollLink()));
  }

  Future<void> _pollLink() async {
    final api = ref.read(meshApiProvider);
    if (api == null || _link == null) return;
    try {
      final result = await api.pollLink();
      if (!mounted) return;
      if (result.pending) return;
      _linkPoll?.cancel();
      setState(() {
        _link = null;
        _status = result.status ?? _status;
      });
      await _refresh();
    } catch (error) {
      if (!mounted) return;
      _linkPoll?.cancel();
      setState(() {
        _link = null;
        _error = _errorText(error);
      });
    }
  }

  /// Flip the switch off while the browser is still open.
  Future<void> _cancelLink() async {
    _linkPoll?.cancel();
    final api = ref.read(meshApiProvider);
    setState(() => _link = null);
    await api?.cancelLink().catchError((_) {});
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
          _renameSection(l10n),
          _treeSection(l10n, _tree),
          _leaveSection(l10n),
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

  Widget _joinCard(L10n l10n) {
    final linking = _link != null;
    final defaultServer = _resolvedDefaultServer();
    return SettingsSection(
      title: l10n.meshJoinTitle,
      footer: Text(l10n.meshJoinHint),
      children: [
        SettingsSwitchTile(
          icon: AppIcons.link,
          title: l10n.meshConnectToggle,
          subtitle: linking
              ? l10n.meshWaitingApproval
              : l10n.meshConnectToggleHint,
          value: linking,
          onChanged: _pairingBusy
              ? null
              : (next) => unawaited(next ? _join() : _cancelLink()),
        ),
        if (_pairingBusy && !linking)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: Row(
              children: [
                const SizedBox.square(dimension: 16, child: CircularProgressIndicator(strokeWidth: 2)),
                const SizedBox(width: 12),
                Text(l10n.meshOpeningBrowser, style: Theme.of(context).textTheme.bodySmall),
              ],
            ),
          ),
        if (defaultServer.isNotEmpty && !linking)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                l10n.meshServerDefaultHint(defaultServer),
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ),
          ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 6, 16, 12),
          child: TextField(
            controller: _server,
            enabled: !_pairingBusy && !linking,
            keyboardType: TextInputType.url,
            autocorrect: false,
            decoration: InputDecoration(
              labelText: l10n.meshServerAddress,
              hintText: l10n.meshServerAddressPlaceholder,
              border: const OutlineInputBorder(),
            ),
            onChanged: (_) => setState(() => _serverTouched = true),
          ),
        ),
        if (linking) ...[
          if (_link!.approvalUrl.isNotEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
              child: Text(
                l10n.meshApprovalFallback,
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ),
          if (_link!.approvalUrl.isNotEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
              child: SelectableText(
                _link!.approvalUrl,
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  fontFamily: 'monospace',
                  color: Theme.of(context).colorScheme.primary,
                ),
              ),
            ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: () => unawaited(_reopenBrowser()),
                    icon: const Icon(AppIcons.openExternal, size: 18),
                    label: Text(l10n.meshOpenBrowserAgain),
                  ),
                ),
                if (_link!.approvalUrl.isNotEmpty) ...[
                  const SizedBox(width: 8),
                  OutlinedButton.icon(
                    onPressed: () => unawaited(_copyApprovalUrl()),
                    icon: const Icon(AppIcons.copy, size: 18),
                    label: Text(l10n.meshCopyLink),
                  ),
                ],
              ],
            ),
          ),
        ],
      ],
    );
  }

  /// Try external browser first (easier to switch browsers), then platform
  /// default and in-app Custom Tabs as fallbacks on restrictive Android ROMs.
  Future<bool> _launchApprovalUrl(String rawUrl) async {
    final uri = Uri.tryParse(rawUrl);
    if (uri == null) return false;
    for (final mode in const [
      LaunchMode.externalApplication,
      LaunchMode.platformDefault,
      LaunchMode.inAppBrowserView,
    ]) {
      try {
        if (await launchUrl(uri, mode: mode)) return true;
      } catch (_) {
        // Try the next launch mode.
      }
    }
    return false;
  }

  Future<void> _copyApprovalUrl() async {
    final url = _link?.approvalUrl ?? '';
    if (url.isEmpty) return;
    await Clipboard.setData(ClipboardData(text: url));
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(context.l10n.meshLinkCopied)));
  }

  Future<void> _reopenBrowser() async {
    final url = _link?.approvalUrl ?? '';
    if (url.isEmpty) return;
    final opened = await _launchApprovalUrl(url);
    if (!opened && mounted) setState(() => _error = context.l10n.meshLinkOpenFailed);
  }

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
    final isExpanded = _expandedPeerId == peer.nodeId;
    final children = <Widget>[
      SettingsTile(
        icon: AppIcons.remoteHost,
        title: peer.name,
        subtitle: peer.online ? l10n.meshOnline : l10n.meshOffline,
        trailing: Icon(isExpanded ? AppIcons.collapse : AppIcons.expand, size: 18),
        chevron: false,
        onTap: () => setState(() => _expandedPeerId = isExpanded ? null : peer.nodeId),
      ),
    ];
    if (isExpanded) {
      final workspaces = [...peer.workspaces]
        ..sort((left, right) {
          final latest = right.latestAt.compareTo(left.latestAt);
          return latest != 0 ? latest : left.name.compareTo(right.name);
        });
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
    }
    return SettingsSection(children: children);
  }

  Widget _sessionTile(L10n l10n, MeshNode peer, MeshWorkspace workspace, MeshSession session) {
    final canChat = session.controllable && peer.online;
    final details = <String>[meshStatusLabel(l10n, session.status)];
    if (session.status == 'idle') {
      final updated = meshUpdatedLabel(context, session.updatedAt);
      if (updated.isNotEmpty) details.add(updated);
    }
    if (!canChat) details.add(l10n.meshReadOnly);
    return SettingsTile(
      icon: canChat ? AppIcons.chat : AppIcons.lock,
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
          controllable: canChat,
        ),
      ),
    );
  }

  Widget _renameSection(L10n l10n) => SettingsSection(
    children: [
      SettingsTile(icon: AppIcons.edit, title: l10n.meshRename, onTap: () => unawaited(_rename())),
    ],
  );

  Widget _leaveSection(L10n l10n) => SettingsSection(
    children: [
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
