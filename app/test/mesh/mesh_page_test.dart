import 'package:dsh_app/state/mesh_state.dart';
import 'package:dsh_app/ui/mesh/mesh_page.dart';
import 'package:dsh_client/dsh_client.dart' show DshConnection;
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../chat/support/harness.dart';

class _FakeMeshApi extends MeshApi {
  _FakeMeshApi({this.paired = true}) : super(DshConnection(Uri.parse('http://127.0.0.1:9')));

  final bool paired;

  @override
  Future<MeshStatus> status() async => MeshStatus(
    paired: paired,
    serverUrl: 'https://mesh.example',
    node: const MeshNode(nodeId: 'phone', name: 'Phone', platform: 'android', online: true, self: true),
    connection: paired ? 'connected' : 'unpaired',
    peersOnline: paired ? 1 : 0,
    peersTotal: paired ? 1 : 0,
  );

  @override
  Future<MeshTree> tree() async => MeshTree.fromJson({
    'cursor': 1,
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
            'title': 'This phone workspace',
            'sessions': [],
          },
        ],
      },
      {
        'nodeId': 'pc',
        'name': 'win32-asus',
        'platform': 'win32',
        'online': true,
        'workspaces': [
          {
            'workspaceId': 'w-orb',
            'title': 'dsh_orb',
            'sessions': [
              {
                'sessionId': 's-task',
                'title': '列出桌面文件并分类汇总',
                'status': 'idle',
                'controllable': true,
                'updatedAt': 1,
              },
            ],
          },
        ],
      },
    ],
  });

  @override
  Future<MeshFrames> page(String nodeId, String sessionId) async => const MeshFrames(events: [], cursor: 0);

  @override
  Future<MeshFrames> stream(String nodeId, String sessionId, int since) async =>
      const MeshFrames(events: [], cursor: 0);
}

void main() {
  testWidgets('shows peers and hides the local device in the tree', (tester) async {
    usePhoneView(tester);
    await tester.pumpWidget(
      testApp(
        const MeshPage(),
        overrides: [meshApiProvider.overrideWithValue(_FakeMeshApi())],
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.textContaining('win32-asus'), findsOneWidget);
    expect(find.text('dsh_orb'), findsOneWidget);
    expect(find.text('列出桌面文件并分类汇总'), findsOneWidget);
    expect(find.text('This phone workspace'), findsNothing);

    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });

  testWidgets('shows the pairing card while unpaired', (tester) async {
    usePhoneView(tester);
    await tester.pumpWidget(
      testApp(
        const MeshPage(),
        overrides: [meshApiProvider.overrideWithValue(_FakeMeshApi(paired: false))],
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.text('加入互联'), findsOneWidget);
    expect(find.text('配对串'), findsOneWidget);
    expect(find.text('连接'), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });
}