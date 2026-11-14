import 'package:dsh_app/state/mesh_state.dart';
import 'package:dsh_app/ui/mesh/mesh_page.dart';
import 'package:dsh_client/dsh_client.dart' show DshConnection;
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../chat/support/harness.dart';

class _FakeMeshApi extends MeshApi {
  _FakeMeshApi({this.paired = true, this.defaultServer = ''})
    : super(DshConnection(Uri.parse('http://127.0.0.1:9')));

  final bool paired;
  final String defaultServer;

  /// What the page last asked the plugin to link, so a test can assert the
  /// switch really reaches `/link` with the address it showed.
  String? startedWith;

  @override
  Future<MeshStatus> status() async => MeshStatus(
    paired: paired,
    serverUrl: 'https://mesh.example',
    node: const MeshNode(nodeId: 'phone', name: 'Phone', platform: 'android', online: true, self: true),
    connection: paired ? 'connected' : 'unpaired',
    peersOnline: paired ? 1 : 0,
    peersTotal: paired ? 1 : 0,
    defaultServer: defaultServer,
  );

  @override
  Future<MeshLinkStart> startLink(String serverUrl, {String? name}) async {
    startedWith = serverUrl;
    return const MeshLinkStart(
      approvalUrl: 'https://mesh.example/app/link/link-1',
      linkId: 'link-1',
      expiresAt: 4102444800000,
    );
  }

  @override
  Future<MeshLinkPoll> pollLink() async => const MeshLinkPoll(pending: true);

  @override
  Future<void> cancelLink() async {}

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

  testWidgets('shows the join switch while unpaired', (tester) async {
    usePhoneView(tester);
    await tester.pumpWidget(
      testApp(
        const MeshPage(),
        overrides: [meshApiProvider.overrideWithValue(_FakeMeshApi(paired: false))],
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.text('加入互联'), findsWidgets);
    expect(find.text('服务器地址'), findsOneWidget);
    expect(find.byType(Switch), findsOneWidget);
    // The pairing-string form belongs to personal mode and is gone from the phone.
    expect(find.text('配对串'), findsNothing);

    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });

  testWidgets('offers the deployment server in the address field', (tester) async {
    usePhoneView(tester);
    await tester.pumpWidget(
      testApp(
        const MeshPage(),
        overrides: [
          meshApiProvider.overrideWithValue(
            _FakeMeshApi(paired: false, defaultServer: 'https://mesh.example'),
          ),
        ],
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.text('https://mesh.example'), findsOneWidget);
    expect(find.textContaining('默认服务器'), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });

  testWidgets('flipping the switch asks the plugin for a link with that address', (tester) async {
    usePhoneView(tester);
    final api = _FakeMeshApi(paired: false, defaultServer: 'https://mesh.example');
    await tester.pumpWidget(
      testApp(const MeshPage(), overrides: [meshApiProvider.overrideWithValue(api)]),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    await tester.tap(find.byType(Switch));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(api.startedWith, 'https://mesh.example');
    // Waiting for the browser is shown, not a form the user has to resubmit.
    expect(find.textContaining('等待确认'), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });

  testWidgets('the switch refuses to start without an address', (tester) async {
    usePhoneView(tester);
    final api = _FakeMeshApi(paired: false);
    await tester.pumpWidget(
      testApp(const MeshPage(), overrides: [meshApiProvider.overrideWithValue(api)]),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    await tester.tap(find.byType(Switch));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(api.startedWith, isNull);
    expect(find.text('请先填写服务器地址。'), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });
}