import 'dart:async';

import 'package:dsh_app/state/mesh_state.dart';
import 'package:dsh_app/ui/mesh/mesh_session_page.dart';
import 'package:dsh_client/dsh_client.dart' show DshConnection;
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../chat/support/harness.dart';

class _SessionApi extends MeshApi {
  _SessionApi(this.frames) : super(DshConnection(Uri.parse('http://127.0.0.1:9')));

  final MeshFrames frames;
  final _pending = Completer<MeshFrames>();

  @override
  Future<MeshStatus> status() async => const MeshStatus(paired: true, connection: 'connected');

  @override
  Future<MeshTree> tree() async => const MeshTree(nodes: [], cursor: 0);

  @override
  Future<MeshFrames> page(String nodeId, String sessionId) async => frames;

  @override
  Future<MeshFrames> stream(String nodeId, String sessionId, int since) => _pending.future;

  @override
  Future<void> prompt(String nodeId, String sessionId, String text) async {}

  @override
  Future<void> cancel(String nodeId, String sessionId) async {}
}

void main() {
  testWidgets('renders remote text and tool rows and offers the composer', (tester) async {
    usePhoneView(tester);
    final frames = MeshFrames(
      cursor: 3,
      events: [
        const MeshEvent(
          seq: 1,
          type: 'user/message',
          data: {
            'source': {'kind': 'user'},
            'content': [
              {'type': 'text', 'text': '看一下桌面'},
            ],
          },
        ),
        const MeshEvent(
          seq: 2,
          type: 'assistant/message',
          data: {
            'message': {
              'content': [
                {'type': 'text', 'text': '## 已完成\n\n文件已回传。'},
              ],
            },
          },
        ),
        const MeshEvent(
          seq: 3,
          type: 'tool/call',
          data: {'callId': 'c1', 'name': 'pwsh', 'arguments': 'dir'},
        ),
      ],
    );
    await tester.pumpWidget(
      testApp(
        const MeshSessionPage(
          nodeId: 'pc',
          sessionId: 's-task',
          title: '列出桌面文件',
          nodeName: 'win32-asus',
          workspaceName: 'dsh_orb',
          controllable: true,
        ),
        overrides: [meshApiProvider.overrideWithValue(_SessionApi(frames))],
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.text('看一下桌面'), findsOneWidget);
    expect(find.textContaining('文件已回传'), findsOneWidget);
    expect(find.textContaining('工具 · pwsh'), findsOneWidget);
    expect(find.text('输入指令，交给远端任务会话…'), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });
}