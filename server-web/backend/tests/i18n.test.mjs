import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';

for (const locale of ['en', 'zh-CN']) {
  test(locale + ': semantic localization preserves arbitrary values', async () => {
    globalThis.localStorage = {getItem: () => locale};
    const i = await import('../web/js/i18n.js?test=' + locale);
    assert.equal(i.locale, locale);
    assert.equal(i.t('密码'), locale === 'en' ? 'Password' : '密码');
    assert.equal(i.count(1, 'device'), locale === 'en' ? '1 device' : '1 台设备');
    assert.equal(i.count(2, 'device'), locale === 'en' ? '2 devices' : '2 台设备');
    assert.equal(i.eventText('connection_failed'), locale === 'en' ? 'Connection failed' : '连接失败');
    assert.equal(i.errorText('密码不正确'), locale === 'en' ? 'Incorrect password' : '密码不正确');
    assert.equal(i.errorText('Incorrect password'), locale === 'en' ? 'Incorrect password' : '密码不正确');
    assert.equal(i.errorText('unexpected secret detail'), i.t('操作没有完成'));
    const name = '配置 · <script>alert(1)</script> 中文 customer';
    assert.equal(i.recordDetail({title: '新增用户', detail: name}), name);
    assert.equal(i.recordDetail({title: '导入配置', detail: '核心配置校验失败'}), '核心配置校验失败');
    assert.equal(i.recordDetail({title: '节点执行结果', detail: name + ' / 服务已启动'}), name + ' / ' + i.t('服务已启动'));
    assert.equal(i.recordDetail({title: '分配线路', detail: name + ' / 1 位用户'}), name + ' / ' + i.count(1, 'customer'));
    assert.equal(i.groupName('默认分组', 'custom-id'), '默认分组');
    assert.equal(i.groupName('默认分组', '00000000-0000-4000-8000-000000000001'), i.t('默认分组'));
    assert.equal(i.groupName(name, '00000000-0000-4000-8000-000000000001'), name);
    assert.equal(i.networkText('cellular'), i.t('移动网络'));
    assert.ok(i.quotation.source.startsWith('https://'));
    for(const [key, value] of Object.entries(i.EN)) assert.ok(!/[\u3400-\u9fff]/u.test(value), key);
  });
}
test('blocked storage and unsupported locales are handled without changing authentication', async () => {
  globalThis.localStorage = {getItem: () => {throw new Error('blocked')}, setItem: () => {throw new Error('blocked')}};
  const i = await import('../web/js/i18n.js?test=blocked');
  assert.equal(i.locale, 'en');
  i.setLocale('ja');
  assert.throws(() => i.setLocale('zh-CN'), /site storage/);
});

for(const locale of ['en','zh-CN']) test(locale + ': every current audit title and generated-detail path',async()=>{
  globalThis.localStorage={getItem:()=>locale};const i=await import('../web/js/i18n.js?records='+locale);
  const titles=['导入配置','分配配置','新增用户','重命名配置','创建接入凭据','准备重装接入','服务器已接入','部署协议','新增出口','保存线路','分配线路','重新下发配置','启用协议','停用协议','检测线路出口','线路出口检测','节点执行结果','客户配置已生成','撤销管理接入','恢复客户服务','收回客户服务','删除客户','拦下 Claude','放行 Claude','保存分组'];
  for(const title of titles){
    const result=i.recordTitle(title);assert.notEqual(result,i.t('系统记录'));if(locale==='en')assert.ok(!/[\u3400-\u9fff]/u.test(result));
    // A user name may equal system copy and must stay intact in the free-text part.
    assert.equal(i.recordDetail({title,detail:'服务已启动'}),'服务已启动');
  }
  assert.equal(i.recordTitle('future_unknown'),i.t('系统记录'));
  for(const message of ['核心配置校验失败','启动失败，已尝试恢复原配置','端口已占用','核心尚未安装','配置已暂存','服务已启动','节点执行失败'])
    assert.equal(i.recordDetail({title:'节点执行结果',detail:'自由文本 / '+message}),'自由文本 / '+i.systemText(message));
  assert.equal(i.recordDetail({title:'分配线路',detail:'自由文本 / 12 位用户'}),'自由文本 / '+i.count(12,'customer'));
  const assigned=i.recordDetail({title:'分配配置',detail:'Original profile → Original customers 等 12 位用户'});
  if(locale==='en')assert.ok(!/[\u3400-\u9fff]/u.test(assigned));else assert.equal(assigned,'Original profile → Original customers 等 12 位用户');
  for(const status of ['完成','等待接入','等待新节点','等待节点执行','未分配','等待节点应用','服务已启动','仅暂存','失败','未通过','客户端未同步','服务未远程停止'])assert.notEqual(i.systemText(status),i.t('未知'));
});
