import 'dart:convert';

import 'package:dsh_client/dsh_client.dart' show DshConnection, DshHttpResponse;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'connection_state.dart';

/// One device in the Mesh tree. The local device is included with `self: true`;
/// the UI hides it because it is already shown in the header.
class MeshNode {
  const MeshNode({
    required this.nodeId,
    required this.name,
    required this.platform,
    required this.online,
    required this.self,
    this.lastSeenAt,
    this.workspaces = const [],
  });

  factory MeshNode.fromJson(Object? raw) {
    final json = _record(raw);
    final nodeId = _string(json['nodeId']);
    final workspaces = <MeshWorkspace>[
      for (final item in _list(json['workspaces'])) MeshWorkspace.fromJson(item, nodeId),
    ];
    return MeshNode(
      nodeId: nodeId,
      name: _string(json['name'], nodeId.isEmpty ? 'device' : nodeId),
      platform: _string(json['platform'], 'unknown'),
      online: json['online'] != false,
      self: json['self'] == true,
      lastSeenAt: _integerOrNull(json['lastSeenAt']),
      workspaces: workspaces,
    );
  }

  final String nodeId;
  final String name;
  final String platform;
  final bool online;
  final bool self;
  final int? lastSeenAt;
  final List<MeshWorkspace> workspaces;

  MeshNode copyWith({List<MeshWorkspace>? workspaces}) => MeshNode(
    nodeId: nodeId,
    name: name,
    platform: platform,
    online: online,
    self: self,
    lastSeenAt: lastSeenAt,
    workspaces: workspaces ?? this.workspaces,
  );
}

class MeshWorkspace {
  const MeshWorkspace({
    required this.nodeId,
    required this.workspaceId,
    required this.name,
    this.path,
    this.sessions = const [],
  });

  factory MeshWorkspace.fromJson(Object? raw, String nodeId) {
    final json = _record(raw);
    final workspaceId = _string(json['workspaceId']);
    return MeshWorkspace(
      nodeId: nodeId,
      workspaceId: workspaceId,
      name: _string(json['title'], _string(json['name'], workspaceId)),
      path: _stringOrNull(json['path']),
      sessions: [
        for (final item in _list(json['sessions'])) MeshSession.fromJson(item, nodeId, workspaceId),
      ],
    );
  }

  final String nodeId;
  final String workspaceId;
  final String name;
  final String? path;
  final List<MeshSession> sessions;

  int get latestAt => sessions.fold(0, (latest, session) => session.updatedAt > latest ? session.updatedAt : latest);
}

class MeshSession {
  const MeshSession({
    required this.nodeId,
    required this.workspaceId,
    required this.sessionId,
    required this.title,
    required this.status,
    required this.controllable,
    required this.updatedAt,
  });

  factory MeshSession.fromJson(Object? raw, String nodeId, String workspaceId) {
    final json = _record(raw);
    final sessionId = _string(json['sessionId']);
    return MeshSession(
      nodeId: nodeId,
      workspaceId: workspaceId,
      sessionId: sessionId,
      title: _string(json['title'], sessionId),
      status: _string(json['status'], 'idle'),
      controllable: json['controllable'] == true,
      updatedAt: _integer(json['updatedAt']),
    );
  }

  final String nodeId;
  final String workspaceId;
  final String sessionId;
  final String title;
  final String status;
  final bool controllable;
  final int updatedAt;

  bool get running => status == 'running';
  bool get waitingApproval => status == 'waiting-approval';
  bool get waitingQuestion => status == 'waiting-question';
  bool get failed => status == 'failed';
}

class MeshTree {
  const MeshTree({required this.nodes, required this.cursor});

  factory MeshTree.fromJson(Object? raw) {
    final json = _record(raw);
    return MeshTree(
      nodes: [for (final item in _list(json['nodes'])) MeshNode.fromJson(item)],
      cursor: _integer(json['cursor']),
    );
  }

  final List<MeshNode> nodes;
  final int cursor;

  /// Devices other than this phone, in the order the host sent them.
  List<MeshNode> get peers => [for (final node in nodes) if (!node.self) node];
}

class MeshStatus {
  const MeshStatus({
    required this.paired,
    this.serverUrl = '',
    this.serverFingerprint = '',
    this.node,
    this.connection = 'unpaired',
    this.peersOnline = 0,
    this.peersTotal = 0,
    this.error,
    this.defaultServer = '',
  });

  factory MeshStatus.fromJson(Object? raw) {
    final json = _record(raw);
    final server = _record(json['server']);
    final node = _record(json['node']);
    final error = _string(json['error']);
    return MeshStatus(
      paired: json['paired'] == true,
      serverUrl: _string(server['url']),
      serverFingerprint: _string(server['fp']),
      node: node.isEmpty ? null : MeshNode.fromJson(node),
      connection: _string(json['connection'], 'unpaired'),
      peersOnline: _integer(json['peersOnline']),
      peersTotal: _integer(json['peersTotal']),
      error: error.isEmpty ? null : error,
      defaultServer: _string(json['defaultServer']),
    );
  }

  final bool paired;
  final String serverUrl;
  final String serverFingerprint;
  final MeshNode? node;
  final String connection;
  final int peersOnline;
  final int peersTotal;

  /// Why the runtime is offline, when the plugin knows; shown instead of a bare
  /// 「离线」 so a startup failure is actionable.
  final String? error;

  /// Origin the sign-in switch offers first. Empty means the deployment bundled
  /// none and the address field has to be filled in.
  final String defaultServer;

  bool get connected => connection == 'connected';
}

/// The approval hand-off the browser has to finish: created by
/// [MeshApi.startLink] and completed by the phone polling [MeshApi.pollLink].
class MeshLinkStart {
  const MeshLinkStart({required this.approvalUrl, required this.linkId, required this.expiresAt});

  factory MeshLinkStart.fromJson(Object? raw) {
    final json = _record(raw);
    return MeshLinkStart(
      approvalUrl: _string(json['approvalUrl']),
      linkId: _string(json['linkId']),
      expiresAt: _integer(json['expiresAt']),
    );
  }

  /// The page the phone opens in the system browser; approving there is what
  /// hands this device its credentials.
  final String approvalUrl;
  final String linkId;

  /// Epoch milliseconds the link stops being collectable.
  final int expiresAt;
}

/// One `/link/poll` answer: `pending` while the browser has not approved yet,
/// `status` once it has and the plugin adopted the credentials.
class MeshLinkPoll {
  const MeshLinkPoll({required this.pending, this.status});

  factory MeshLinkPoll.fromJson(Object? raw) {
    final json = _record(raw);
    final status = _record(json['status']);
    return MeshLinkPoll(
      pending: json['pending'] != false,
      status: status.isEmpty ? null : MeshStatus.fromJson(status),
    );
  }

  final bool pending;
  final MeshStatus? status;
}

class MeshEvent {
  const MeshEvent({required this.seq, required this.type, required this.data});

  final int seq;
  final String type;
  final Map<String, Object?> data;
}

class MeshFrames {
  const MeshFrames({required this.events, required this.cursor});

  final List<MeshEvent> events;
  final int cursor;
}

/// One `device_agent` task, from the local `/.dsh-mesh/v1/tasks` route. The
/// floating strip uses `originNodeId` to keep only tasks this phone dispatched.
class MeshTaskFile {
  const MeshTaskFile({
    required this.name,
    required this.size,
    required this.direction,
    required this.state,
  });

  factory MeshTaskFile.fromJson(Object? raw) {
    final json = _record(raw);
    return MeshTaskFile(
      name: _string(json['name']),
      size: _integer(json['size']),
      direction: _string(json['direction']),
      state: _string(json['state']),
    );
  }

  final String name;
  final int size;
  final String direction;
  final String state;
}

class MeshTask {
  const MeshTask({
    required this.taskId,
    required this.originNodeId,
    required this.targetNodeId,
    required this.sessionId,
    required this.status,
    required this.createdAt,
    required this.updatedAt,
    this.workspaceId,
    this.prompt,
    this.callerSessionId,
    this.files = const [],
  });

  factory MeshTask.fromJson(Object? raw) {
    final json = _record(raw);
    final workspace = _string(json['workspaceId']);
    final prompt = _string(json['prompt']);
    final caller = _string(json['callerSessionId']);
    return MeshTask(
      taskId: _string(json['taskId']),
      originNodeId: _string(json['originNodeId']),
      targetNodeId: _string(json['targetNodeId']),
      sessionId: _string(json['sessionId']),
      status: _string(json['status'], 'unknown'),
      createdAt: _integer(json['createdAt']),
      updatedAt: _integer(json['updatedAt']),
      workspaceId: workspace.isEmpty ? null : workspace,
      prompt: prompt.isEmpty ? null : prompt,
      callerSessionId: caller.isEmpty ? null : caller,
      files: [for (final item in _list(json['files'])) MeshTaskFile.fromJson(item)],
    );
  }

  final String taskId;
  final String originNodeId;
  final String targetNodeId;
  final String sessionId;

  /// running | waiting-input | done | failed | cancelled | unknown
  final String status;
  final int createdAt;
  final int updatedAt;
  final String? workspaceId;

  /// The asking text, for the bookmark + task cards.
  final String? prompt;

  /// The local conversation that called `device_agent`, for attribution.
  final String? callerSessionId;
  final List<MeshTaskFile> files;

  bool get running => status == 'running' || status == 'waiting-input';
}

class MeshApiException implements Exception {
  const MeshApiException(this.statusCode, this.code, this.message);

  final int statusCode;
  final String code;
  final String message;

  @override
  String toString() => message;
}

/// The phone-facing `/.dsh-mesh/v1` client. It uses the local engine's
/// authenticated [DshConnection], so the cookie, host fence and launch token
/// stay in the same place as every other local DSH request.
class MeshApi {
  MeshApi(this.connection);

  final DshConnection connection;

  static const _root = '/.dsh-mesh/v1';

  Future<MeshStatus> status() async => MeshStatus.fromJson(await _get('/status'));

  Future<MeshTree> tree() async => MeshTree.fromJson(await _get('/tree'));

  /// The device tasks that involve this node, for the bookmark strip and task
  /// cards. The contract's route answers a bare array; tolerate `{tasks}` too.
  Future<List<MeshTask>> tasks() async {
    final decoded = await _getAny('/tasks');
    final list = decoded is List ? decoded : (decoded is Map ? _list(decoded['tasks']) : const <Object?>[]);
    return [for (final item in list) MeshTask.fromJson(item)];
  }

  Future<List<MeshNode>> nodes() async {
    final decoded = await _getAny('/nodes');
    final list = decoded is List ? decoded : (decoded is Map ? _list(decoded['nodes']) : const <Object?>[]);
    return [for (final item in list) MeshNode.fromJson(item)];
  }

  /// Like [_get] but the body may be an array (`/tasks`, `/nodes`).
  Future<Object?> _getAny(String path) async {
    final response = await connection.get('$_root$path');
    if (response.statusCode < 200 || response.statusCode >= 300) {
      Map<String, Object?> body = const {};
      if (response.body.isNotEmpty) {
        try {
          final decoded = jsonDecode(response.text);
          if (decoded is Map) body = decoded.cast<String, Object?>();
        } catch (_) {
          // The error message below still covers plain text.
        }
      }
      final code = _string(body['error'], 'http_${response.statusCode}');
      final message = _string(body['message'], response.text.trim());
      throw MeshApiException(response.statusCode, code, message.isEmpty ? code : message);
    }
    if (response.body.isEmpty) return null;
    try {
      return jsonDecode(response.text);
    } catch (_) {
      return null;
    }
  }

  Future<MeshFrames> page(String nodeId, String sessionId) async {
    final body = await _get('/sessions/${_segment(nodeId)}/${_segment(sessionId)}/page');
    final events = <MeshEvent>[];
    for (final record in _list(body['records'])) {
      final row = _record(record);
      if (_string(row['type']) != 'event') continue;
      final event = _event(row['event']);
      if (event != null) events.add(event);
    }
    return _frames(events, _integer(body['cursor']));
  }

  Future<MeshFrames> stream(String nodeId, String sessionId, int since) async {
    final body = await _get(
      '/sessions/${_segment(nodeId)}/${_segment(sessionId)}/stream',
      query: {'since': '$since'},
    );
    final events = <MeshEvent>[];
    for (final item in _list(body['items'])) {
      final row = _record(item);
      final event = _event(row['event'], seq: _integer(row['seq']));
      if (event != null) events.add(event);
    }
    return _frames(events, _integer(body['cursor']));
  }

  Future<void> prompt(String nodeId, String sessionId, String text) async {
    await _post('/sessions/${_segment(nodeId)}/${_segment(sessionId)}/prompt', {'text': text});
  }

  Future<void> cancel(String nodeId, String sessionId) async {
    await _post('/sessions/${_segment(nodeId)}/${_segment(sessionId)}/cancel', const <String, Object?>{});
  }

  Future<void> pair(String pairing, {String? name}) async {
    await _post('/pair', {
      'pairing': pairing,
      if (name != null && name.trim().isNotEmpty) 'name': name.trim(),
    });
  }

  /// Hosted mode, step 1: ask for a link and get the browser URL back.
  ///
  /// Returns immediately — the approval happens in the browser, and this phone
  /// must not hold a request open for it.
  Future<MeshLinkStart> startLink(String serverUrl, {String? name}) async =>
      MeshLinkStart.fromJson(await _post('/link', {
        'serverUrl': serverUrl,
        if (name != null && name.trim().isNotEmpty) 'name': name.trim(),
      }));

  /// Hosted mode, step 2: one collect attempt. `pending` means keep polling.
  Future<MeshLinkPoll> pollLink() async =>
      MeshLinkPoll.fromJson(await _post('/link/poll', const <String, Object?>{}));

  /// Stop waiting for an approval the user gave up on.
  Future<void> cancelLink() async => _post('/link/cancel', const <String, Object?>{});

  /// Whether a link is still waiting for its browser approval.
  Future<bool> linkPending() async {
    final body = await _get('/link/status');
    return body['pending'] == true;
  }

  Future<void> unpair() async => _post('/unpair', const <String, Object?>{});

  Future<void> rename(String name) async => _post('/name', {'name': name});

  Future<Map<String, Object?>> _get(String path, {Map<String, String>? query}) async {
    final response = await connection.get('$_root$path', query: query);
    return _decode(response);
  }

  Future<Map<String, Object?>> _post(String path, Object? body) async {
    final response = await connection.postJson('$_root$path', body);
    return _decode(response);
  }

  Map<String, Object?> _decode(DshHttpResponse response) {
    Map<String, Object?> body = const {};
    if (response.body.isNotEmpty) {
      try {
        final decoded = jsonDecode(response.text);
        if (decoded is Map) body = decoded.cast<String, Object?>();
      } catch (_) {
        // A proxy or an older host may answer with plain text; the status/error
        // below still carries the useful part.
      }
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      final code = _string(body['error'], 'http_${response.statusCode}');
      final message = _string(body['message'], response.text.trim());
      throw MeshApiException(response.statusCode, code, message.isEmpty ? code : message);
    }
    return body;
  }

  static String _segment(String value) => Uri.encodeComponent(value);

  static MeshFrames _frames(List<MeshEvent> events, int cursor) {
    events.sort((left, right) => left.seq.compareTo(right.seq));
    final seen = <int>{};
    final unique = <MeshEvent>[];
    for (final event in events) {
      if (event.seq < 0 || !seen.add(event.seq)) continue;
      unique.add(event);
    }
    final last = unique.isEmpty ? 0 : unique.last.seq;
    return MeshFrames(events: unique, cursor: cursor > last ? cursor : last);
  }

  static MeshEvent? _event(Object? raw, {int? seq}) {
    final event = _record(raw);
    final type = _string(event['type']);
    final number = seq ?? _integer(event['seq'], -1);
    if (type.isEmpty || number < 0) return null;
    return MeshEvent(seq: number, type: type, data: _record(event['data']));
  }
}

final meshApiProvider = Provider<MeshApi?>((ref) {
  final connection = ref.watch(localConnectionProvider).value;
  if (connection == null) return null;
  return MeshApi(connection.client.connection);
});

/// A transcript row reduced from durable DSH events. The page does not try to
/// re-create the whole chat renderer; it keeps the same event semantics as the
/// desktop Mesh window and hands text to the existing Markdown widget.
enum MeshRowKind { user, assistant, reasoning, tool }

class MeshRow {
  const MeshRow({
    required this.id,
    required this.kind,
    required this.text,
    this.toolName = '',
    this.args = '',
    this.result = '',
    this.isError = false,
  });

  final String id;
  final MeshRowKind kind;
  final String text;
  final String toolName;
  final String args;
  final String result;
  final bool isError;
}

class _RowBuilder {
  _RowBuilder({
    required this.id,
    required this.kind,
    required this.text,
    this.toolName = '',
  });

  final String id;
  MeshRowKind kind;
  String text;
  String toolName;
  String args = '';
  String result = '';
  bool isError = false;

  MeshRow build() => MeshRow(
    id: id,
    kind: kind,
    text: text,
    toolName: toolName,
    args: args,
    result: result,
    isError: isError,
  );
}

List<MeshRow> reduceMeshEvents(Iterable<MeshEvent> source) {
  final events = source.toList()..sort((left, right) => left.seq.compareTo(right.seq));
  final rows = <_RowBuilder>[];
  final byId = <String, _RowBuilder>{};

  _RowBuilder upsert(String id, _RowBuilder Function() create) {
    final existing = byId[id];
    if (existing != null) return existing;
    final row = create();
    rows.add(row);
    byId[id] = row;
    return row;
  }

  for (final event in events) {
    final data = event.data;
    switch (event.type) {
      case 'user/message':
        final source = _record(data['source']);
        final kind = _string(source['kind']);
        if (kind.isNotEmpty && kind != 'user') continue;
        final text = _contentText(data['content']);
        if (text.trim().isEmpty) continue;
        upsert('user:${event.seq}', () => _RowBuilder(id: 'user:${event.seq}', kind: MeshRowKind.user, text: text));
      case 'assistant/message':
        final message = _record(data['message']);
        final content = message['content'];
        if (content is String) {
          if (content.trim().isNotEmpty) {
            upsert('assistant:${event.seq}', () => _RowBuilder(id: 'assistant:${event.seq}', kind: MeshRowKind.assistant, text: content));
          }
          continue;
        }
        final parts = _list(content);
        for (var index = 0; index < parts.length; index += 1) {
          final part = _record(parts[index]);
          final type = _string(part['type']);
          if (type == 'tool-call' || type == 'tool_use') {
            final name = _string(part['name']);
            if (name.isEmpty) continue;
            final callId = _string(part['id'], _string(part['callId']));
            final id = callId.isEmpty ? 'tool:${event.seq}:$index' : 'tool:$callId';
            final row = upsert(id, () => _RowBuilder(id: id, kind: MeshRowKind.tool, text: name, toolName: name));
            row.toolName = name;
            row.args = _arguments(part['arguments']);
            continue;
          }
          final text = _string(part['text']);
          if (text.trim().isEmpty) continue;
          final reasoning = type == 'reasoning' || type == 'thinking';
          final kind = reasoning ? MeshRowKind.reasoning : MeshRowKind.assistant;
          final id = '${reasoning ? 'reasoning' : 'assistant'}:${event.seq}:$index';
          upsert(id, () => _RowBuilder(id: id, kind: kind, text: text));
        }
      case 'tool/call':
        final name = _string(data['name']);
        if (name.isEmpty) continue;
        final callId = _string(data['callId']);
        final id = callId.isEmpty ? 'tool:${event.seq}' : 'tool:$callId';
        final row = upsert(id, () => _RowBuilder(id: id, kind: MeshRowKind.tool, text: name, toolName: name));
        row.toolName = name;
        row.args = _arguments(data['arguments']);
      case 'tool/result':
        final message = _record(data['message']);
        final source = _record(message['source']);
        final callId = _string(data['callId'], _string(message['toolCallId'], _string(source['callId'])));
        if (callId.isEmpty) continue;
        final id = 'tool:$callId';
        final existing = byId[id];
        final name = existing?.toolName ?? _string(data['name'], 'tool');
        final row = upsert(id, () => _RowBuilder(id: id, kind: MeshRowKind.tool, text: name, toolName: name));
        row.result = _contentText(message['content']);
        row.isError = message['isError'] == true || data['isError'] == true;
      default:
        // turn/start, turn/end, titles, inbox splices and assistant deltas do
        // not add a row; the status chip and the next message carry the state.
        break;
    }
  }
  return [for (final row in rows) row.build()];
}

Map<String, Object?> _record(Object? value) =>
    value is Map ? value.cast<String, Object?>() : const <String, Object?>{};

List<Object?> _list(Object? value) => value is List ? value : const <Object?>[];

String _string(Object? value, [String fallback = '']) => value is String ? value : fallback;

String? _stringOrNull(Object? value) => value is String && value.isNotEmpty ? value : null;

int _integer(Object? value, [int fallback = 0]) => value is num ? value.toInt() : fallback;

int? _integerOrNull(Object? value) => value is num ? value.toInt() : null;

String _contentText(Object? content) {
  if (content is String) return content;
  final parts = <String>[];
  for (final part in _list(content)) {
    final row = _record(part);
    if (_string(row['type']) != 'text') continue;
    final text = _string(row['text']);
    if (text.isNotEmpty) parts.add(text);
  }
  return parts.join('\n');
}

String _arguments(Object? value) {
  if (value is String) return value;
  if (value == null) return '';
  try {
    return jsonEncode(value);
  } catch (_) {
    return '$value';
  }
}