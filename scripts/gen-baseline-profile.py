#!/usr/bin/env python3
"""从 R8 mapping.txt 生成 App 启动路径的 baseline profile 规则(HRF)。

用法(在仓库根目录):
    python3 scripts/gen-baseline-profile.py <mapping.txt> <输出 baseline-prof.txt>

mapping.txt 来自 CI 产物 r8-mapping-<run_id>(CI job "Media App (Android)" 上传),
对应同一提交的 release R8 构建。规则选择冷启动会走到的自有代码包前缀,统一标记
HSPL(启动热),另加关键类预分配规则;输出可直接放在
media_app/app/src/main/baseline-prof.txt。

为什么需要手工生成:R8 混淆后 profile 中的类/方法名不可读;AGP 构建期会用
mapping 把 HRF 的源码名映射到混淆名并合并进 APK 的 assets/dexopt/baseline.prof,
因此必须用源码签名编写规则。修改启动路径后重新跑一次 CI、下载 mapping、重生成即可。

注意:通配符规则在 AGP 9.2.1 的 app 模块实测不生效(PR 实验),必须展开成显式
类/方法规则;生成器即负责展开。
"""
import re, sys
from collections import OrderedDict

mapping_path = sys.argv[1]
out_path = sys.argv[2]

PREFIXES = [
    'com.example.mediacenter.MediaCenterApp',
    'com.example.mediacenter.ComposeActivity',
    'com.example.mediacenter.ui.AppNavigationKt',
    'com.example.mediacenter.ui.theme.',
    'com.example.mediacenter.ui.home.',
    'com.example.mediacenter.ui.components.',
    'com.example.mediacenter.ui.player.PlayerViewModel',
    'com.example.mediacenter.ui.player.PlayerScreenKt',
    'com.example.mediacenter.ui.player.PlayerContentKt',
    'com.example.mediacenter.ui.detail.',
    'com.example.mediacenter.ui.search.',
    'com.example.mediacenter.log.',
    'com.example.mediacenter.net.',
    'com.example.mediacenter.util.StartupTrace',
    'com.example.mediacenter.util.DevicePerformance',
    'com.example.mediacenter.util.BackgroundWork',
    'com.example.mediacenter.util.Jank',
    'com.example.mediacenter.data.MediaRepositoryApi',
    'com.example.mediacenter.data.MediaRepositoryHolder',
    'com.example.mediacenter.data.MediaRepository',
    'com.example.mediacenter.data.MediaApi',
    'com.example.mediacenter.data.HttpCall',
    'com.example.mediacenter.data.HomeSnapshot',
    'com.example.mediacenter.data.WatchHistory',
    'com.example.mediacenter.data.SearchHistory',
    'com.example.mediacenter.data.PlaybackPrefetcher',
    'com.example.mediacenter.data.PlaybackPosition',
    'com.example.mediacenter.data.PlaybackStorageKey',
    'com.example.mediacenter.data.PlaybackTuning',
    'com.example.mediacenter.data.Models',
    'com.example.mediacenter.data.VideoSummary',
    'com.example.mediacenter.data.ContentCategory',
    'com.example.mediacenter.data.ProviderInfo',
    'com.example.mediacenter.data.PlaybackResult',
    'com.example.mediacenter.data.PlaybackOption',
    'com.example.mediacenter.data.QualityOption',
    'com.example.mediacenter.data.PlaybackPages',
    'com.example.mediacenter.data.VideosResponse',
    'com.example.mediacenter.data.ProvidersResponse',
    'com.example.mediacenter.data.CategoriesResponse',
    'com.example.mediacenter.data.SearchResponse',
    'com.example.mediacenter.data.ProviderBootstrapResponse',
]

PRIMITIVES = {
    'void': 'V', 'boolean': 'Z', 'byte': 'B', 'char': 'C', 'short': 'S',
    'int': 'I', 'long': 'J', 'float': 'F', 'double': 'D',
}

def norm_class(c):
    if c.startswith('L') and c.endswith(';'):
        return c[1:-1].replace('/', '.')
    return c

def type_desc(t):
    t = t.strip()
    if not t:
        return ''
    if t.endswith('...'):
        t = t[:-3] + '[]'
    if t.endswith('[]'):
        return '[' + type_desc(t[:-2])
    if t in PRIMITIVES:
        return PRIMITIVES[t]
    return 'L' + t.replace('.', '/') + ';'

method_re = re.compile(r'^\s*(?:(?:\d+:\d+):)?([^\s]+) ([^\s(]+)\(([^)]*)\)(?::\d+)*(?::\d+)? -> (\S+)\s*$')
class_re = re.compile(r'^([^\s#].*?) -> (\S+):\s*$')

cur_class = None
methods = OrderedDict()  # (orig_class, name, params) -> ret
classes = []

with open(mapping_path, encoding='utf-8', errors='ignore') as f:
    for line in f:
        if not line.strip():
            continue
        if line.startswith('#'):
            continue
        if not line.startswith(' '):
            m = class_re.match(line)
            if m:
                cur_class = norm_class(m.group(1))
                classes.append(cur_class)
            else:
                cur_class = None
            continue
        if cur_class is None:
            continue
        m = method_re.match(line)
        if not m:
            continue
        ret, name, params, new_name = m.group(1), m.group(2), m.group(3), m.group(4)
        if '.' in name:
            if not name.startswith(cur_class + '.'):
                continue
            name = name[len(cur_class) + 1:]
        params = [p.strip() for p in params.split(',') if p.strip()]
        methods.setdefault((cur_class, name, tuple(params)), ret)

selected_classes = [c for c in classes if any(c == p or c.startswith(p) for p in PREFIXES)]
selected = {c for c in selected_classes}

rules = []
seen = set()
for (cls, name, params), ret in methods.items():
    if cls not in selected:
        continue
    key = (cls, name, params)
    if key in seen:
        continue
    seen.add(key)
    desc = 'L' + cls.replace('.', '/') + ';'
    param_desc = ''.join(type_desc(p) for p in params)
    ret_desc = type_desc(ret)
    if name == '<clinit>':
        continue
    method_name = name
    rules.append(f'HSP{desc}->{method_name}({param_desc}){ret_desc}')

# 关键类预分配(class rule)
CLASS_RULES = [
    'com.example.mediacenter.MediaCenterApp',
    'com.example.mediacenter.ComposeActivity',
    'com.example.mediacenter.ui.home.HomeViewModel',
    'com.example.mediacenter.ui.home.HomeScreenKt',
    'com.example.mediacenter.ui.AppNavigationKt',
    'com.example.mediacenter.data.MediaRepository',
    'com.example.mediacenter.data.MediaApi',
    'com.example.mediacenter.log.AppLog',
    'com.example.mediacenter.util.DevicePerformance',
    'com.example.mediacenter.util.StartupTrace',
]
class_rules = [f'L{c.replace(".", "/")};' for c in CLASS_RULES if c in selected]

with open(out_path, 'w', encoding='utf-8') as f:
    f.write('\n'.join(rules + class_rules) + '\n')

print(f'selected classes: {len(selected_classes)}, method rules: {len(rules)}, class rules: {len(class_rules)}')
