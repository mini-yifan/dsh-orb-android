// The floating strip's bookmarks: background_agent tasks on this phone and
// device_agent tasks on the Mesh. Mirrors the desktop strip's rules: running
// bookmarks stay, a finished bookmark is unread until clicked (which consumes
// it) or dismissed with ×, and a continued session is a new stretch.

import 'dart:async';
import 'dart:convert';

import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/foundation.dart' show listEquals;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../state/chat_state.dart';
import '../state/hosts_state.dart';
import '../state/mesh_state.dart';
import '../state/sessions_state.dart';
import 'orb_session.dart';
import 'overlay_channel.dart';

enum OrbBookmarkKind { background, device }

class OrbBookmark {
  const OrbBookmark({
    required this.id,
    required this.kind,
    required this.sessionId,
    this.nodeId,
    required this.title,
    required this.status,
    required this.startedAt,
    this.endedAt,
    required this.unread,
    this.deviceName,
  });

  /// `bg:<session>` or `dev:<node>:<session>`.
  final String id;
  final OrbBookmarkKind kind;
  final String sessionId;
  final String? nodeId;
  final String title;

  /// running | waiting | completed | stopped | ended
  final String status;
  final int startedAt;
  final int? endedAt;
  final bool unread;
  final String? deviceName;

  bool get active => status == 'running' || status == 'waiting';

  /// Read/dismissed is per stretch, so a continued session gets a fresh unread.
  String get stretch => '$id@$startedAt';

  Map<String, Object?> toMap() => {
    'id': id,
    'kind': kind == OrbBookmarkKind.device ? 'device' : 'background',
    'sessionId': sessionId,
    if (nodeId != null) 'nodeId': nodeId,
    'title': title,
    'status': status,
    'startedAt': startedAt,
    if (endedAt != null) 'endedAt': endedAt,
    'unread': unread,
    if (deviceName != null) 'deviceName': deviceName,
  };

  @override
  bool operator ==(Object other) =>
      other is OrbBookmark &&
      other.id == id &&
      other.kind == kind &&
      other.sessionId == sessionId &&
      other.nodeId == nodeId &&
      other.title == title &&
      other.status == status &&
      other.startedAt == startedAt &&
      other.endedAt == endedAt &&
      other.unread == unread &&
      other.deviceName == deviceName;

  @override
  int get hashCode => Object.hash(
    id,
    kind,
    sessionId,
    nodeId,
    title,
    status,
    startedAt,
    endedAt,
    unread,
    deviceName,
  );
}

/// One background stretch, keyed by its local session.
class OrbBackgroundTask {
  OrbBackgroundTask(this.task, this.startedAt);
  String task;
  int startedAt;
  int? endedAt;
}

/// One `background_agent` call that has a session id.
class OrbBackgroundDispatch {
  const OrbBackgroundDispatch(this.callId, this.sessionId, this.task);
  final String callId;
  final String sessionId;
  final String task;
}

/// The succeeded `background_agent` calls in a transcript, oldest first.
List<OrbBackgroundDispatch> backgroundDispatches(ChatState chat) {
  final out = <OrbBackgroundDispatch>[];
  for (final tool in chat.items.whereType<ToolChatItem>()) {
    if (tool.name != 'background_agent' || tool.status != ToolStatus.succeeded) continue;
    final args = _jsonMap(tool.arguments);
    final result = _jsonMap(tool.resultText);
    final sid = _text(result?['session_id']) ??
        _text(args?['session_id']) ??
        _sessionIdFromText(tool.resultText);
    if (sid == null) continue;
    final task = _text(args?['task']) ?? _text(result?['task']) ?? '后台任务';
    out.add(OrbBackgroundDispatch(tool.callId, sid, task));
  }
  return out;
}

/// The local background bookmark for one stretch.
OrbBookmark backgroundBookmark({
  required String sessionId,
  required OrbBackgroundTask task,
  required bool running,
  required Set<String> read,
  required Set<String> dismissed,
}) {
  final id = 'bg:$sessionId';
  final stretch = '$id@${task.startedAt}';
  return OrbBookmark(
    id: id,
    kind: OrbBookmarkKind.background,
    sessionId: sessionId,
    title: task.task,
    status: running ? 'running' : 'completed',
    startedAt: task.startedAt,
    endedAt: running ? null : task.endedAt,
    unread: !running && !read.contains(stretch) && !dismissed.contains(stretch),
  );
}

/// One Mesh task dispatched by this phone, or null when it belongs elsewhere.
OrbBookmark? deviceBookmark({
  required MeshTask task,
  required String? localNodeId,
  required String? deviceName,
  required Set<String> read,
  required Set<String> dismissed,
}) {
  if (localNodeId == null || task.originNodeId != localNodeId) return null;
  final status = switch (task.status) {
    'running' => 'running',
    'waiting-input' => 'waiting',
    'done' => 'completed',
    'cancelled' => 'stopped',
    'failed' => 'ended',
    _ => 'ended',
  };
  final active = status == 'running' || status == 'waiting';
  final id = 'dev:${task.targetNodeId}:${task.sessionId}';
  final stretch = '$id@${task.createdAt}';
  return OrbBookmark(
    id: id,
    kind: OrbBookmarkKind.device,
    sessionId: task.sessionId,
    nodeId: task.targetNodeId,
    title: task.prompt ?? '任务',
    status: status,
    startedAt: task.createdAt,
    endedAt: active ? null : task.updatedAt,
    unread: !active && !read.contains(stretch) && !dismissed.contains(stretch),
    deviceName: deviceName,
  );
}

class OrbBookmarksController extends Notifier<List<OrbBookmark>> {
  final _seenCalls = <String>{};
  final _background = <String, OrbBackgroundTask>{};
  final _read = <String>{};
  final _dismissed = <String>{};
  List<SessionSummary> _sessions = const [];
  List<MeshTask> _tasks = const [];
  List<MeshNode> _nodes = const [];
  String? _localNodeId;
  Timer? _timer;
  bool _busy = false;

  @override
  List<OrbBookmark> build() {
    final target = ref.watch(orbSessionProvider);
    if (target != null) {
      _sessions = ref.read(sessionsProvider).value ?? const [];
      _absorb(ref.read(chatProvider(target.sessionId)));
      ref.listen<ChatState>(chatProvider(target.sessionId), (_, next) {
        _absorb(next);
        _emit();
      });
    }
    ref.listen<AsyncValue<List<SessionSummary>>>(sessionsProvider, (_, next) {
      _sessions = next.value ?? const [];
      _emit();
    });
    _timer = Timer.periodic(const Duration(seconds: 5), (_) => unawaited(_refreshMesh()));
    unawaited(_refreshMesh());
    ref.onDispose(() => _timer?.cancel());
    return _compute();
  }

  /// A finished bookmark is consumed by a click; a running one never is.
  void markRead(String id) {
    final bookmark = state.where((b) => b.id == id).firstOrNull;
    if (bookmark == null || bookmark.active) return;
    if (_read.add(bookmark.stretch)) _emit();
  }

  void dismiss(String id) {
    final bookmark = state.where((b) => b.id == id).firstOrNull;
    if (bookmark == null || bookmark.active) return;
    if (_dismissed.add(bookmark.stretch)) _emit();
  }

  void _emit() {
    final next = _compute();
    if (listEquals(next, state)) return;
    state = next;
  }

  /// Each succeeded call is one stretch; continuing a session starts a new one.
  void _absorb(ChatState chat) {
    for (final dispatch in backgroundDispatches(chat)) {
      if (!_seenCalls.add(dispatch.callId)) continue;
      final existing = _background[dispatch.sessionId];
      if (existing != null) {
        existing.task = dispatch.task;
        existing.startedAt = DateTime.now().millisecondsSinceEpoch;
        existing.endedAt = null;
      } else {
        _background[dispatch.sessionId] = OrbBackgroundTask(
          dispatch.task,
          DateTime.now().millisecondsSinceEpoch,
        );
      }
    }
  }

  Future<void> _refreshMesh() async {
    if (_busy) return;
    _busy = true;
    try {
      final api = ref.read(meshApiProvider);
      if (api == null) {
        _tasks = const [];
        _nodes = const [];
        _localNodeId = null;
        _emit();
        return;
      }
      final status = await api.status();
      final tasks = await api.tasks();
      final nodes = await api.nodes();
      _localNodeId = status.node?.nodeId;
      _tasks = tasks;
      _nodes = nodes;
      _emit();
    } catch (_) {
      // Keep the last known tasks across an engine restart.
    } finally {
      _busy = false;
    }
  }

  List<OrbBookmark> _compute() {
    final out = <OrbBookmark>[];
    for (final entry in _background.entries) {
      final session = _sessions.where((s) => s.sessionId == entry.key).firstOrNull;
      final running = session == null || session.running;
      if (running) {
        entry.value.endedAt = null;
      } else {
        entry.value.endedAt ??= session.updatedAt.millisecondsSinceEpoch;
      }
      out.add(
        backgroundBookmark(
          sessionId: entry.key,
          task: entry.value,
          running: running,
          read: _read,
          dismissed: _dismissed,
        ),
      );
    }
    if (_localNodeId != null) {
      for (final task in _tasks) {
        final bookmark = deviceBookmark(
          task: task,
          localNodeId: _localNodeId,
          deviceName: _nodeName(task.targetNodeId),
          read: _read,
          dismissed: _dismissed,
        );
        if (bookmark != null) out.add(bookmark);
      }
    }
    out.sort((a, b) {
      final rank = _rank(a.status).compareTo(_rank(b.status));
      if (rank != 0) return rank;
      return (b.endedAt ?? b.startedAt).compareTo(a.endedAt ?? a.startedAt);
    });
    return out;
  }

  String? _nodeName(String nodeId) =>
      _nodes.where((n) => n.nodeId == nodeId).firstOrNull?.name;

  static int _rank(String status) => switch (status) {
    'running' => 0,
    'waiting' => 1,
    _ => 2,
  };
}

final orbBookmarksProvider = NotifierProvider<OrbBookmarksController, List<OrbBookmark>>(
  OrbBookmarksController.new,
);

/// Feeds `pushBookmarks` while the local host is active; a remote active host
/// hides the strip (and with it the bookmarks).
final orbBookmarksBridgeProvider = Provider<void>((ref) {
  final channel = ref.watch(petOverlayChannelProvider);
  final local = ref.watch(activeHostProvider) == HostRef.local;
  final items = ref.watch(orbBookmarksProvider);
  unawaited(
    channel.pushBookmarks(
      !local || items.isEmpty
          ? const {'visible': false}
          : {
              'visible': true,
              'items': [for (final b in items) b.toMap()],
            },
    ),
  );
});

String? _text(Object? value) {
  if (value is! String) return null;
  final text = value.trim();
  return text.isEmpty ? null : text;
}

Map<String, Object?>? _jsonMap(String text) {
  final trimmed = text.trim();
  if (trimmed.isEmpty) return null;
  try {
    final decoded = jsonDecode(trimmed);
    if (decoded is Map) return decoded.cast<String, Object?>();
  } catch (_) {
    // A plain-text result is handled by the session-id fallback.
  }
  return null;
}

String? _sessionIdFromText(String text) {
  final match = RegExp(r'"session_id"\s*:\s*"([^"]+)"').firstMatch(text);
  return match?.group(1);
}