import 'dart:async';

import 'package:dsh_app/state/mesh_state.dart';
import 'package:dsh_app/ui/mesh/mesh_page.dart';
import 'package:dsh_client/dsh_client.dart' show DshConnection;
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import '../chat/support/harness.dart';

class _FakeMeshApi extends MeshApi {
  _FakeMeshApi({
    this.paired = true,
    this.defaultServer = '',
    this.pcSessions,
    this.macOnline = true,
  }) : super(DshConnection(Uri.parse('http://127.0.0.1:9')));

  final bool paired;
  final String defaultServer;
  final List<Map<String, Object?>>? pcSessions;
  final bool macOnline;

  /// What the page last asked the plugin to link, so a test can assert the
  /// switch really reaches `/link` with the address it showed.
  String? startedWith;

  /// Records `(nodeId, workspaceId)` passed to `createSession`.
  final List<(String, String)> createdCalls = [];
  final _pendingStream = Completer<MeshFrames>();

  @override
  Future<MeshSession> createSession(String nodeId, String workspaceId) async {
    createdCalls.add((nodeId, workspaceId));
    return MeshSession(
      nodeId: nodeId,
      workspaceId: workspaceId,
      sessionId: 's-created-${createdCalls.length}',
      title: '新创建的远端会话',
      status: 'idle',
      controllable: true,
      updatedAt: 999,
    );
  }

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
            'sessions': pcSessions ?? [
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
      {
        'nodeId': 'mac',
        'name': 'mac-studio',
        'platform': 'darwin',
        'online': macOnline,
        'workspaces': [
          {
            'workspaceId': 'w-mac',
            'title': 'Mac Workspace',
            'sessions': [
              {
                'sessionId': 's-normal',
                'title': '普通日常对话',
                'status': 'idle',
                'controllable': true,
                'updatedAt': 2,
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
  Future<MeshFrames> stream(String nodeId, String sessionId, int since) => _pendingStream.future;
}

Future<void> _tapVisible(WidgetTester tester, Finder finder) async {
  await Scrollable.ensureVisible(tester.element(finder), alignment: 0.5);
  await tester.pump();
  await tester.tap(finder);
  await tester.pump();
}

void main() {
  testWidgets('collapses peer workspaces by default, toggles one device at a time, and orders rename before peers', (
    tester,
  ) async {
    usePhoneView(tester);
    await tester.pumpWidget(
      testApp(
        const MeshPage(),
        overrides: [meshApiProvider.overrideWithValue(_FakeMeshApi())],
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.text('win32-asus'), findsOneWidget);
    expect(find.text('mac-studio'), findsOneWidget);
    expect(find.text('dsh_orb'), findsNothing);
    expect(find.text('列出桌面文件并分类汇总'), findsNothing);
    expect(find.text('Mac Workspace'), findsNothing);
    expect(find.text('This phone workspace'), findsNothing);

    // Rename stays above peers; leave interconnect stays at the bottom.
    final renameY = tester.getTopLeft(find.text('修改本机名称')).dy;
    final peerY = tester.getTopLeft(find.text('win32-asus')).dy;
    final leaveY = tester.getTopLeft(find.text('退出互联')).dy;
    expect(renameY, lessThan(peerY));
    expect(peerY, lessThan(leaveY));

    // Tap first peer: expands its workspace and session.
    await tester.tap(find.text('win32-asus'));
    await tester.pump();
    expect(find.text('dsh_orb'), findsOneWidget);
    expect(find.text('列出桌面文件并分类汇总'), findsOneWidget);
    expect(find.text('Mac Workspace'), findsNothing);

    // Tap second peer: collapses the first and opens the second.
    await tester.tap(find.text('mac-studio'));
    await tester.pump();
    expect(find.text('dsh_orb'), findsNothing);
    expect(find.text('Mac Workspace'), findsOneWidget);
    expect(find.text('普通日常对话'), findsOneWidget);

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
    // Waiting for the browser is shown, along with the copy-link action and fallback hint.
    expect(find.textContaining('等待确认'), findsOneWidget);
    expect(find.text('复制链接'), findsOneWidget);
    expect(find.textContaining('换一个浏览器试试'), findsWidgets);

    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });

  testWidgets('the switch falls back to the built-in default server when none is configured', (tester) async {
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

    expect(api.startedWith, 'https://dshorb.top');
    expect(find.textContaining('等待确认'), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });

  testWidgets('expands and collapses long workspace lists and resets to preview after workspace/device toggle', (
    tester,
  ) async {
    usePhoneView(tester);
    final many = [
      for (var i = 1; i <= 7; i += 1)
        {
          'sessionId': 's-$i',
          'title': '会话条目 $i',
          'status': 'idle',
          'controllable': true,
          'updatedAt': 100 - i,
        },
    ];
    final api = _FakeMeshApi(pcSessions: many);
    await tester.pumpWidget(
      testApp(
        const MeshPage(),
        overrides: [meshApiProvider.overrideWithValue(api)],
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    // Expand pc -> initially shows first 5 sessions + "展开其余 2 个会话".
    await _tapVisible(tester, find.text('win32-asus'));
    expect(find.text('会话条目 1'), findsOneWidget);
    expect(find.text('会话条目 5'), findsOneWidget);
    expect(find.text('会话条目 6'), findsNothing);
    expect(find.text('展开其余 2 个会话'), findsOneWidget);

    // Expand all -> shows session 6, 7 and bottom "收起" button.
    await _tapVisible(tester, find.text('展开其余 2 个会话'));
    expect(find.text('会话条目 6'), findsOneWidget);
    expect(find.text('会话条目 7'), findsOneWidget);
    expect(find.text('收起'), findsOneWidget);

    // Tap bottom "收起" -> back to 5-session preview.
    await _tapVisible(tester, find.text('收起'));
    expect(find.text('会话条目 6'), findsNothing);
    expect(find.text('展开其余 2 个会话'), findsOneWidget);

    // Expand all again, then tap workspace folder to collapse whole workspace.
    await _tapVisible(tester, find.text('展开其余 2 个会话'));
    expect(find.text('会话条目 7'), findsOneWidget);

    await _tapVisible(tester, find.text('dsh_orb'));
    expect(find.text('会话条目 1'), findsNothing);
    expect(find.text('收起'), findsNothing);

    // Re-tap workspace folder -> back to initial 5-session preview, NOT all 7.
    await _tapVisible(tester, find.text('dsh_orb'));
    expect(find.text('会话条目 1'), findsOneWidget);
    expect(find.text('会话条目 5'), findsOneWidget);
    expect(find.text('会话条目 6'), findsNothing);
    expect(find.text('展开其余 2 个会话'), findsOneWidget);

    // Expand all, collapse workspace via device switch, then come back -> initial expanded workspace & 5-session preview.
    await _tapVisible(tester, find.text('展开其余 2 个会话'));
    await _tapVisible(tester, find.text('dsh_orb'));
    await _tapVisible(tester, find.text('mac-studio'));
    await _tapVisible(tester, find.text('win32-asus'));
    expect(find.text('会话条目 1'), findsOneWidget);
    expect(find.text('会话条目 6'), findsNothing);

    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });

  testWidgets('creates a new remote session from the workspace plus button and hides plus for offline peers', (
    tester,
  ) async {
    usePhoneView(tester);
    final api = _FakeMeshApi(macOnline: false);
    await tester.pumpWidget(
      testApp(
        const MeshPage(),
        overrides: [meshApiProvider.overrideWithValue(api)],
      ),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    // Offline peer shows no '+' button when expanded.
    await tester.tap(find.text('mac-studio'));
    await tester.pump();
    expect(find.byTooltip('新建对话'), findsNothing);

    // Online peer shows '+' button; tapping creates session and opens MeshSessionPage.
    await tester.tap(find.text('win32-asus'));
    await tester.pump();
    expect(find.byTooltip('新建对话'), findsOneWidget);
    await tester.tap(find.byTooltip('新建对话'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(api.createdCalls, [('pc', 'w-orb')]);
    expect(find.text('新创建的远端会话'), findsOneWidget);
    expect(find.text('输入消息，继续这段远端会话…'), findsOneWidget);

    await tester.pumpWidget(const SizedBox());
    await tester.pump();
  });
}