// DSH Orb's update sources: the self-hosted manifest (OSS copy) is the feed;
// release files live on GitHub Releases and are downloaded through the bundled
// accelerators first, with the OSS copy as the last-resort fallback.

import 'package:dsh_app/update/update_config.dart';
import 'package:flutter_test/flutter_test.dart';

const _oss = 'https://dsh-orb-release.oss-cn-beijing.aliyuncs.com/app';
const _feed = '$_oss/update.json';
const _apk = 'dsh-orb-0.1.7-arm64.bin';
const _githubApk = 'https://github.com/mini-yifan/dsh-orb-android/releases/download/v0.1.7/$_apk';
const _ossApk = '$_oss/download/0.1.7/$_apk';

void main() {
  group('defaults', () {
    final sources = buildUpdateSources();

    test('feed is the self-hosted manifest; accelerators come before the OSS copy', () {
      expect(sources.channel, isNull);
      expect(sources.feed.toString(), _feed);
      expect(sources.mirrors.map((m) => m.id), ['ghproxy', 'ghfast', 'ghproxynet', 'dshgo']);
      // The OSS copy first; each accelerator can also proxy the GitHub-hosted
      // `update.json` release asset if the OSS feed is down.
      expect(sources.metadataEndpoints().map((e) => e.id),
          ['feed', 'manifest:ghproxy', 'manifest:ghfast', 'manifest:ghproxynet']);
      expect(sources.useGithubApi, isFalse);
    });

    test('a GitHub release file is fetched through each accelerator, then the OSS copy', () {
      final urls = sources.downloadUrls([Uri.parse(_githubApk)]);
      expect(urls.map((u) => u.url.toString()), [
        'https://gh-proxy.com/$_githubApk',
        'https://ghfast.top/$_githubApk',
        'https://ghproxy.net/$_githubApk',
        _ossApk,
      ]);
      expect(urls.map((u) => u.mirrorId), ['ghproxy', 'ghfast', 'ghproxynet', 'dshgo']);
    });

    test('a URL that is already on our own host is left alone', () {
      final urls = sources.downloadUrls([Uri.parse(_ossApk)]);
      expect(urls.map((u) => u.url.toString()), [_ossApk]);
      expect(urls.single.mirrorId, isNull);
    });

    test('the shipped configuration is the same as the builder default', () {
      final shipped = defaultUpdateSources();
      expect(shipped.feed.toString(), _feed);
      expect(shipped.mirrors.map((m) => m.id), sources.mirrors.map((m) => m.id));
    });
  });

  group('build-time overrides', () {
    test('moving the self-hosted copy keeps it as the last fallback', () {
      final sources = buildUpdateSources(mirror: 'https://dl.example.com');
      expect(sources.feed.toString(), 'https://dl.example.com/update.json');
      expect(sources.mirrors.last.id, 'dshgo');
      final urls = sources.downloadUrls([Uri.parse(_githubApk)]);
      expect(urls.last.url.toString(), 'https://dl.example.com/download/0.1.7/$_apk');
    });

    test('replacing the accelerator list keeps it ahead of the OSS copy', () {
      final sources = buildUpdateSources(accelerators: 'https://a.example/,https://b.example/');
      expect(sources.mirrors.map((m) => m.id), ['custom0', 'custom1', 'dshgo']);
      final urls = sources.downloadUrls([Uri.parse(_githubApk)]);
      expect(urls.map((u) => u.url.toString()).take(3), [
        'https://a.example/$_githubApk',
        'https://b.example/$_githubApk',
        _ossApk,
      ]);
    });

    test('an empty feed define leaves only the GitHub-hosted manifests', () {
      final sources = buildUpdateSources(feed: '');
      expect(sources.feed, isNull);
      expect(sources.metadataEndpoints().map((e) => e.id),
          ['manifest:ghproxy', 'manifest:ghfast', 'manifest:ghproxynet']);
    });
  });
}
