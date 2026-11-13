import 'package:dsh_app/pets/orb_bookmarks.dart';
import 'package:dsh_app/state/chat_state.dart';
import 'package:dsh_app/state/mesh_state.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter_test/flutter_test.dart';

ToolChatItem _tool({
  String callId = 'c1',
  String name = 'background_agent',
  String arguments = '{"task":"整理照片"}',
  String result = '{"accepted":true,"created":true,"session_id":"s1"}',
  ToolStatus status = ToolStatus.succeeded,
}) => ToolChatItem(
  callId: callId,
  name: name,
  arguments: arguments,
  status: status,
  resultParts: [TextPart(result)],
);

MeshTask _task({
  String originNodeId = 'me',
  String targetNodeId = 'mac',
  String sessionId = 's1',
  String status = 'done',
  String? prompt = '改文档',
}) => MeshTask(
  taskId: 't1',
  originNodeId: originNodeId,
  targetNodeId: targetNodeId,
  sessionId: sessionId,
  status: status,
  createdAt: 1000,
  updatedAt: 5000,
  prompt: prompt,
);

void main() {
  test('a succeeded background_agent call yields its session and task', () {
    final dispatches = backgroundDispatches(ChatState(items: [_tool()]));
    expect(dispatches, hasLength(1));
    expect(dispatches.single.sessionId, 's1');
    expect(dispatches.single.task, '整理照片');
    expect(dispatches.single.callId, 'c1');
  });

  test('unfinished, other tools and non-JSON results are handled', () {
    expect(backgroundDispatches(ChatState(items: [_tool(status: ToolStatus.running)])), isEmpty);
    expect(backgroundDispatches(ChatState(items: [_tool(name: 'bash')])), isEmpty);
    final text = backgroundDispatches(
      ChatState(items: [_tool(result: 'accepted: session_id="s1"')]),
    );
    expect(text, isEmpty);
    final embedded = backgroundDispatches(
      ChatState(items: [_tool(result: '{"session_id":"s2","accepted":true}')]),
    );
    expect(embedded.single.sessionId, 's2');
  });

  test('a running background bookmark is never unread; a finish is until read', () {
    final task = OrbBackgroundTask('整理照片', 1000);
    final running = backgroundBookmark(
      sessionId: 's1',
      task: task,
      running: true,
      read: const {},
      dismissed: const {},
    );
    expect(running.status, 'running');
    expect(running.unread, false);

    task.endedAt = 5000;
    final done = backgroundBookmark(
      sessionId: 's1',
      task: task,
      running: false,
      read: const {},
      dismissed: const {},
    );
    expect(done.status, 'completed');
    expect(done.unread, true);
    expect(done.stretch, 'bg:s1@1000');

    final read = backgroundBookmark(
      sessionId: 's1',
      task: task,
      running: false,
      read: const {'bg:s1@1000'},
      dismissed: const {},
    );
    expect(read.unread, false);

    final dismissed = backgroundBookmark(
      sessionId: 's1',
      task: task,
      running: false,
      read: const {},
      dismissed: const {'bg:s1@1000'},
    );
    expect(dismissed.unread, false);
  });

  test('a device task is kept only when this phone dispatched it', () {
    final bookmark = deviceBookmark(
      task: _task(),
      localNodeId: 'me',
      deviceName: '书房 Mac',
      read: const {},
      dismissed: const {},
    );
    expect(bookmark, isNotNull);
    expect(bookmark!.id, 'dev:mac:s1');
    expect(bookmark.kind, OrbBookmarkKind.device);
    expect(bookmark.status, 'completed');
    expect(bookmark.unread, true);
    expect(bookmark.deviceName, '书房 Mac');

    final other = deviceBookmark(
      task: _task(),
      localNodeId: 'another',
      deviceName: null,
      read: const {},
      dismissed: const {},
    );
    expect(other, isNull);
  });

  test('device status maps and the stretch is consumed by read or dismiss', () {
    String statusOf(String raw) => deviceBookmark(
      task: _task(status: raw),
      localNodeId: 'me',
      deviceName: null,
      read: const {},
      dismissed: const {},
    )!.status;

    expect(statusOf('running'), 'running');
    expect(statusOf('waiting-input'), 'waiting');
    expect(statusOf('done'), 'completed');
    expect(statusOf('cancelled'), 'stopped');
    expect(statusOf('failed'), 'ended');
    expect(statusOf('unknown'), 'ended');

    final read = deviceBookmark(
      task: _task(),
      localNodeId: 'me',
      deviceName: null,
      read: const {'dev:mac:s1@1000'},
      dismissed: const {},
    );
    expect(read!.unread, false);

    final dismissed = deviceBookmark(
      task: _task(),
      localNodeId: 'me',
      deviceName: null,
      read: const {},
      dismissed: const {'dev:mac:s1@1000'},
    );
    expect(dismissed!.unread, false);
  });
}