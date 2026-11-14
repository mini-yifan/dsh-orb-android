import 'package:dsh_app/state/mesh_state.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('mesh models', () {
    test('parses status and keeps the local device addressable', () {
      final status = MeshStatus.fromJson({
        'paired': true,
        'server': {'url': 'https://mesh.example:443', 'fp': 'fp'},
        'node': {'nodeId': 'phone', 'name': 'Phone', 'platform': 'android', 'online': true},
        'connection': 'connected',
        'peersOnline': 1,
        'peersTotal': 2,
      });
      expect(status.paired, isTrue);
      expect(status.connected, isTrue);
      expect(status.serverUrl, 'https://mesh.example:443');
      expect(status.node?.name, 'Phone');
      expect(status.peersOnline, 1);
    });

    test('reads the default server and the offline reason the plugin reports', () {
      final status = MeshStatus.fromJson({
        'paired': false,
        'connection': 'unpaired',
        'peersOnline': 0,
        'peersTotal': 0,
        'defaultServer': 'https://mesh.example',
      });
      expect(status.defaultServer, 'https://mesh.example');

      final failed = MeshStatus.fromJson({
        'paired': true,
        'server': {'url': 'https://mesh.example', 'fp': ''},
        'connection': 'offline',
        'peersOnline': 0,
        'peersTotal': 0,
        'error': 'boom',
      });
      // A startup failure is shown instead of a bare 「离线」, so this has to survive parsing.
      expect(failed.error, 'boom');
      expect(failed.connected, isFalse);
    });

    test('parses a link start and keeps pending polls distinct from an approved one', () {
      final started = MeshLinkStart.fromJson({
        'approvalUrl': 'https://mesh.example/app/link/link-1',
        'linkId': 'link-1',
        'expiresAt': 4102444800000,
      });
      expect(started.approvalUrl, 'https://mesh.example/app/link/link-1');
      expect(started.linkId, 'link-1');

      final pending = MeshLinkPoll.fromJson({'pending': true});
      expect(pending.pending, isTrue);
      expect(pending.status, isNull);

      final approved = MeshLinkPoll.fromJson({
        'pending': false,
        'status': {
          'paired': true,
          'server': {'url': 'https://mesh.example', 'fp': ''},
          'connection': 'connected',
          'peersOnline': 0,
          'peersTotal': 0,
        },
      });
      expect(approved.pending, isFalse);
      expect(approved.status?.paired, isTrue);
    });

    test('parses a nested tree and attaches node/workspace ids to sessions', () {
      final tree = MeshTree.fromJson({
        'cursor': 7,
        'nodes': [
          {
            'nodeId': 'phone',
            'name': 'Phone',
            'platform': 'android',
            'online': true,
            'self': true,
            'workspaces': [
              {
                'workspaceId': 'w-self',
                'title': 'This phone',
                'sessions': [
                  {'sessionId': 's-self', 'title': 'Self', 'status': 'idle', 'controllable': false},
                ],
              },
            ],
          },
          {
            'nodeId': 'pc',
            'name': 'PC',
            'platform': 'win32',
            'online': true,
            'workspaces': [
              {
                'workspaceId': 'w-orb',
                'title': 'dsh_orb',
                'path': r'C:\Users\me\.dsh\dsh_orb',
                'sessions': [
                  {
                    'sessionId': 's-task',
                    'title': 'Task',
                    'status': 'running',
                    'controllable': true,
                    'updatedAt': 123,
                  },
                ],
              },
            ],
          },
        ],
      });
      expect(tree.peers.map((node) => node.nodeId), ['pc']);
      final workspace = tree.peers.single.workspaces.single;
      expect(workspace.latestAt, 123);
      final session = workspace.sessions.single;
      expect(session.nodeId, 'pc');
      expect(session.workspaceId, 'w-orb');
      expect(session.controllable, isTrue);
      expect(session.running, isTrue);
    });
  });

  group('mesh transcript reducer', () {
    MeshEvent event(int seq, String type, Map<String, Object?> data) =>
        MeshEvent(seq: seq, type: type, data: data);

    test('keeps real user messages and drops runtime context', () {
      final rows = reduceMeshEvents([
        event(1, 'user/message', {
          'source': {'kind': 'system'},
          'content': [
            {'type': 'text', 'text': 'runtime context'},
          ],
        }),
        event(2, 'user/message', {
          'source': {'kind': 'user'},
          'content': [
            {'type': 'text', 'text': '看一下桌面'},
          ],
        }),
      ]);
      expect(rows, hasLength(1));
      expect(rows.single.kind, MeshRowKind.user);
      expect(rows.single.text, '看一下桌面');
    });

    test('reduces assistant text, reasoning, and tool results', () {
      final rows = reduceMeshEvents([
        event(1, 'assistant/message', {
          'turn': 1,
          'step': 1,
          'message': {
            'content': [
              {'type': 'reasoning', 'text': '先列目录'},
              {'type': 'tool-call', 'id': 'call-1', 'name': 'pwsh', 'arguments': '{"command":"dir"}'},
              {'type': 'text', 'text': '已完成。'},
            ],
          },
        }),
        event(2, 'tool/result', {
          'message': {
            'role': 'tool',
            'isError': false,
            'source': {'kind': 'tool', 'callId': 'call-1'},
            'content': [
              {'type': 'text', 'text': 'file.txt'},
            ],
          },
        }),
      ]);
      expect(rows.map((row) => row.kind), [
        MeshRowKind.reasoning,
        MeshRowKind.tool,
        MeshRowKind.assistant,
      ]);
      final tool = rows[1];
      expect(tool.toolName, 'pwsh');
      expect(tool.args, '{"command":"dir"}');
      expect(tool.result, 'file.txt');
      expect(tool.isError, isFalse);
    });

    test('attaches a tool result by callId even when the result has no name', () {
      final rows = reduceMeshEvents([
        event(1, 'tool/call', {'callId': 'c9', 'name': 'bash', 'arguments': 'ls'}),
        event(2, 'tool/result', {
          'callId': 'c9',
          'message': {
            'content': [
              {'type': 'text', 'text': 'ok'},
            ],
            'isError': true,
          },
        }),
      ]);
      expect(rows, hasLength(1));
      expect(rows.single.toolName, 'bash');
      expect(rows.single.args, 'ls');
      expect(rows.single.result, 'ok');
      expect(rows.single.isError, isTrue);
    });
  });
}