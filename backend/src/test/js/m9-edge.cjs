'use strict';

const {spawn} = require('node:child_process');
const {createWriteStream} = require('node:fs');
const fs = require('node:fs/promises');
const path = require('node:path');
const {setTimeout: delay} = require('node:timers/promises');

async function until(label, check, timeout = 30_000) {
  const deadline = performance.now() + timeout;
  while (performance.now() < deadline) {
    const result = await check();
    if (result) return result;
    await delay(60);
  }
  throw new Error(label + ' timed out');
}

async function startServer(java, args, directory) {
  const log = createWriteStream(path.join(directory, 'server.log'), {flags: 'wx'});
  const child = spawn(java, args, {cwd: directory, windowsHide: true});
  let address;
  let failure;
  child.on('error', error => { failure = error.code; });
  child.stdout.on('data', bytes => {
    log.write(bytes);
    address ||= bytes.toString().match(/http:\/\/127\.0\.0\.1:\d+/)?.[0];
  });
  child.stderr.on('data', bytes => log.write(bytes));
  child.once('close', () => log.end());
  try {
    const origin = await until('Loopback workbench startup', () => {
      if (failure || child.exitCode !== null) throw new Error('Workbench server failed to start');
      return address;
    });
    return {origin, close: () => child.kill()};
  } catch (error) {
    child.kill();
    throw error;
  }
}

async function edge(executable, directory) {
  const profile = path.join(directory, 'edge-profile');
  const child = spawn(executable, [
    '--headless=new', '--remote-debugging-port=0', '--user-data-dir=' + profile,
    '--disable-background-networking', '--disable-sync', '--no-first-run',
    '--no-default-browser-check', 'about:blank'
  ], {cwd: directory, windowsHide: true, stdio: 'ignore'});
  let failure;
  child.on('error', error => { failure = error.code; });
  let client;
  try {
    const port = await until('Edge DevTools', async () => {
      if (failure || child.exitCode !== null) throw new Error('Edge failed to start');
      try {
        const published = (await fs.readFile(path.join(profile, 'DevToolsActivePort'), 'utf8')).split(/\r?\n/)[0];
        return /^\d+$/.test(published) ? published : null;
      } catch (error) {
        if (error.code !== 'ENOENT' && error.code !== 'EBUSY') throw error;
        return null;
      }
    });
    const address = 'http://127.0.0.1:' + port;
    const version = await (await fetch(address + '/json/version', {signal: AbortSignal.timeout(5000)})).json();
    const targets = await (await fetch(address + '/json/list', {signal: AbortSignal.timeout(5000)})).json();
    client = await connect(targets.find(target => target.type === 'page').webSocketDebuggerUrl);
    return {
      ...client, version: version.Browser,
      close: async () => {
        await Promise.race([client.send('Browser.close').catch(() => {}), delay(2000)]);
        client.socket.close();
        if (child.exitCode === null) child.kill();
      }
    };
  } catch (error) {
    client?.socket.close();
    child.kill();
    throw error;
  }
}

async function connect(url) {
  const socket = new WebSocket(url);
  await new Promise((resolve, reject) => {
    socket.addEventListener('open', resolve, {once: true});
    socket.addEventListener('error', reject, {once: true});
    AbortSignal.timeout(5000).addEventListener('abort', () => reject(new Error('DevTools socket timed out')));
  });
  let next = 0;
  const pending = new Map();
  socket.addEventListener('message', event => {
    const message = JSON.parse(event.data);
    const call = pending.get(message.id);
    if (call) {
      pending.delete(message.id);
      call(message);
    }
  });
  socket.addEventListener('close', () => {
    for (const call of pending.values()) call({error: {message: 'DevTools socket closed'}});
    pending.clear();
  });
  function send(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++next;
      const timeout = setTimeout(() => {
        pending.delete(id);
        reject(new Error(method + ' timed out'));
      }, 35_000);
      pending.set(id, message => {
        clearTimeout(timeout);
        if (message.error) reject(new Error(method + ' failed: ' + message.error.message));
        else resolve(message.result);
      });
      socket.send(JSON.stringify({id, method, params}));
    });
  }
  async function evaluate(expression) {
    const response = await send('Runtime.evaluate', {expression, awaitPromise: true, returnByValue: true});
    if (response.exceptionDetails) throw new Error(response.exceptionDetails.exception?.description || 'Browser evaluation failed');
    return response.result.value;
  }
  return {send, evaluate, socket};
}

async function key(page, key, windowsVirtualKeyCode, text) {
  await page.send('Input.dispatchKeyEvent', {type: text ? 'keyDown' : 'rawKeyDown', key, windowsVirtualKeyCode, text});
  await page.send('Input.dispatchKeyEvent', {type: 'keyUp', key, windowsVirtualKeyCode});
}

module.exports = {edge, startServer, until, key};
