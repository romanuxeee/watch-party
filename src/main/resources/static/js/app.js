(() => {
  'use strict';

  const $ = (selector) => document.querySelector(selector);
  const screens = { login: $('#screen-login'), lobby: $('#screen-lobby'), room: $('#screen-room') };

  const el = {
    loginForm: $('#login-form'), username: $('#username'), loginBtn: $('#login-btn'),
    loginError: $('#login-error'), inviteHint: $('#invite-hint'),
    lobbyAvatar: $('#lobby-avatar'), lobbyUsername: $('#lobby-username'), welcome: $('#welcome'),
    logoutBtn: $('#logout-btn'), createBtn: $('#create-btn'),
    joinForm: $('#join-form'), roomCodeInput: $('#room-code'), lobbyError: $('#lobby-error'),
    codeText: $('#room-code-text'), copyCode: $('#copy-code'), copyLink: $('#copy-link'),
    roleBadge: $('#role-badge'), leaveBtn: $('#leave-btn'),
    player: $('#player'), noVideo: $('#no-video'), loading: $('#loading'),
    loadingText: $('#loading-text'), loadingBar: $('#loading-bar'), loadingFill: $('#loading-fill'),
    nowPlaying: $('#now-playing'),
    sourceForm: $('#source-form'), videoUrl: $('#video-url'), loadBtn: $('#load-btn'),
    uploadLabel: $('#upload-label'), fileInput: $('#file-input'),
    backBtn: $('#back-btn'), fwdBtn: $('#fwd-btn'), syncBtn: $('#sync-btn'),
    people: $('#people'), peopleCount: $('#people-count'),
    messages: $('#messages'), chatForm: $('#chat-form'), chatInput: $('#chat-input'),
    toasts: $('#toasts'),
  };

  const S = {
    config: { appName: 'WatchParty', publicUrl: '', minNameLength: 3, maxNameLength: 24, driftToleranceSeconds: 1.5, guestControl: true },
    username: '',
    code: '',
    me: null,
    isHost: false,
    guestControl: true,
    ws: null,
    inRoom: false,
    leaving: false,
    reconnectTries: 0,
    pingTimer: null,
    latest: null,
    currentSrc: null,
    suppressUntil: 0,
  };

  // ---utils

  function show(name) {
    for (const [key, node] of Object.entries(screens)) node.hidden = key !== name;
  }

  function toast(text, isError) {
    const node = document.createElement('div');
    node.className = 'toast' + (isError ? ' error' : '');
    node.textContent = text;
    el.toasts.appendChild(node);
    setTimeout(() => node.remove(), isError ? 7000 : 4000);
  }

  function showError(node, text) {
    node.textContent = text || '';
    node.hidden = !text;
  }

  async function api(path, options) {
    const response = await fetch(path, options);
    let data = null;
    try { data = await response.json(); } catch (_) { /* пустой ответ */ }
    if (!response.ok) throw new Error((data && data.message) || 'Ошибка сервера (' + response.status + ')');
    return data;
  }

  function store(key, value) {
    try {
      if (value == null) localStorage.removeItem(key); else localStorage.setItem(key, value);
    } catch (_) { /* хранилище недоступно */ }
  }
  function load(key) {
    try { return localStorage.getItem(key); } catch (_) { return null; }
  }

  async function copyText(text) {
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
        return true;
      }
    } catch (_) { /* пробуем запасной способ */ }
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    let ok = false;
    try { ok = document.execCommand('copy'); } catch (_) { ok = false; }
    area.remove();
    return ok;
  }

  function colorFor(name) {
    let h = 0;
    for (const ch of name) h = (h * 31 + ch.codePointAt(0)) % 360;
    return 'hsl(' + h + ', 55%, 38%)';
  }

  function paintAvatar(node, name) {
    node.textContent = (name.trim()[0] || '?').toUpperCase();
    node.style.background = colorFor(name);
  }

  function clock(ms) {
    return new Date(ms).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
  }

  function readHashCode() {
    return decodeURIComponent(location.hash.replace(/^#/, '')).trim().toUpperCase();
  }


  function login(name) {
    const clean = name.trim().replace(/\s+/g, ' ');
    if (clean.length < S.config.minNameLength) {
      showError(el.loginError, 'Минимальная длина имени — ' + S.config.minNameLength + '.');
      return false;
    }
    showError(el.loginError, '');
    S.username = clean;
    store('wp.username', clean);
    el.welcome.textContent = 'Добро пожаловать, ' + clean + '!';
    el.lobbyUsername.textContent = clean;
    paintAvatar(el.lobbyAvatar, clean);
    show('lobby');
    return true;
  }

  function logout() {
    store('wp.username', null);
    S.username = '';
    el.username.value = '';
    el.loginBtn.disabled = true;
    show('login');
  }

  async function createRoom() {
    showError(el.lobbyError, '');
    el.createBtn.disabled = true;
    try {
      const data = await api('/api/rooms', { method: 'POST' });
      connect(data.code);
    } catch (e) {
      showError(el.lobbyError, e.message);
    } finally {
      el.createBtn.disabled = false;
    }
  }

  async function joinRoom(rawCode) {
    const code = rawCode.trim().toUpperCase();
    if (!code) {
      showError(el.lobbyError, 'Введите код комнаты.');
      return;
    }
    showError(el.lobbyError, '');
    try {
      await api('/api/rooms/' + encodeURIComponent(code));
      connect(code);
    } catch (e) {
      showError(el.lobbyError, e.message);
    }
  }


  function send(payload) {
    if (S.ws && S.ws.readyState === WebSocket.OPEN) {
      S.ws.send(JSON.stringify(payload));
      return true;
    }
    return false;
  }

  function connect(code) {
    S.code = code;
    S.leaving = false;
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(scheme + '://' + location.host + '/ws');
    S.ws = ws;

    ws.onopen = () => send({ type: 'JOIN', code, username: S.username });
    ws.onmessage = (event) => {
      let msg;
      try { msg = JSON.parse(event.data); } catch (_) { return; }
      handleMessage(msg);
    };
    ws.onclose = () => {
      if (ws !== S.ws) return;
      stopPing();
      if (S.leaving) return;
      if (S.inRoom && S.reconnectTries < 6) {
        S.reconnectTries += 1;
        toast('Соединение потеряно. Переподключаемся…', true);
        setTimeout(() => { if (!S.leaving && ws === S.ws) connect(S.code); }, Math.min(1000 * 2 ** S.reconnectTries, 8000));
      } else {
        exitRoom('Соединение с сервером потеряно.');
      }
    };
  }

  function startPing() {
    stopPing();
    S.pingTimer = setInterval(() => send({ type: 'PING' }), 25000);
  }
  function stopPing() {
    clearInterval(S.pingTimer);
    S.pingTimer = null;
  }

  function handleMessage(msg) {
    switch (msg.type) {
      case 'JOINED': onJoined(msg); break;
      case 'JOIN_FAILED':
        S.leaving = true;
        if (S.ws) S.ws.close();
        exitRoom(msg.message);
        break;
      case 'PARTICIPANTS': renderParticipants(msg.participants); break;
      case 'STATE': applyState(msg); break;
      case 'CHAT': addChat(msg); break;
      case 'SYSTEM': addSystem(msg.text); break;
      case 'LOADING': showLoading(msg); break;
      case 'ERROR':
        hideLoading();
        toast(msg.message, true);
        break;
      default: break;
    }
  }

  function onJoined(msg) {
    S.me = msg.you;
    S.guestControl = msg.guestControl;
    S.inRoom = true;
    S.reconnectTries = 0;
    S.username = msg.you.name;
    S.code = msg.code;

    el.codeText.textContent = msg.code;
    el.messages.textContent = '';
    msg.history.forEach(addChat);
    renderParticipants(msg.participants);
    show('room');
    history.replaceState(null, '', '#' + msg.code);
    startPing();
    applyState({ reason: 'SYNC', state: msg.state, byId: null });
  }

  function exitRoom(message) {
    S.inRoom = false;
    S.me = null;
    S.latest = null;
    stopPing();
    resetPlayer();
    hideLoading();
    history.replaceState(null, '', location.pathname);
    show(S.username ? 'lobby' : 'login');
    if (message) showError(el.lobbyError, message);
  }

  function leaveRoom() {
    S.leaving = true;
    if (S.ws) S.ws.close();
    exitRoom('');
  }

  // participants and chat

  function renderParticipants(list) {
    el.people.textContent = '';
    el.peopleCount.textContent = String(list.length);
    for (const person of list) {
      const li = document.createElement('li');
      const avatar = document.createElement('span');
      avatar.className = 'avatar';
      paintAvatar(avatar, person.name);
      const name = document.createElement('span');
      name.textContent = person.name;
      li.append(avatar, name);
      if (S.me && person.id === S.me.id) {
        const you = document.createElement('span');
        you.className = 'you';
        you.textContent = '(вы)';
        li.appendChild(you);
        S.isHost = person.host;
      }
      if (person.host) {
        const tag = document.createElement('span');
        tag.className = 'host-tag';
        tag.textContent = 'хост';
        li.appendChild(tag);
      }
      el.people.appendChild(li);
    }
    el.roleBadge.textContent = S.isHost ? 'Вы хост' : 'Вы гость';
    el.roleBadge.classList.toggle('host', S.isHost);
    updateControlsAccess();
  }

  function updateControlsAccess() {
    const allowed = S.guestControl || S.isHost;
    for (const node of [el.videoUrl, el.loadBtn, el.backBtn, el.fwdBtn]) node.disabled = !allowed;
    el.fileInput.disabled = !allowed;
    el.uploadLabel.setAttribute('aria-disabled', String(!allowed));
    el.uploadLabel.style.pointerEvents = allowed ? '' : 'none';
    el.uploadLabel.style.opacity = allowed ? '' : '.45';
    el.videoUrl.placeholder = allowed
      ? 'Ссылка на YouTube или на файл .mp4 / .webm'
      : 'Видео выбирает хост комнаты';
  }

  function pushMessage(li) {
    const stick = el.messages.scrollTop + el.messages.clientHeight >= el.messages.scrollHeight - 40;
    el.messages.appendChild(li);
    if (stick) el.messages.scrollTop = el.messages.scrollHeight;
  }

  function addChat(msg) {
    const li = document.createElement('li');
    const author = document.createElement('span');
    author.className = 'author';
    author.textContent = msg.user;
    author.style.color = 'hsl(' + colorFor(msg.user).match(/\d+/)[0] + ', 70%, 72%)';
    const text = document.createElement('span');
    text.textContent = msg.text;
    const time = document.createElement('span');
    time.className = 'time';
    time.textContent = clock(msg.time);
    li.append(author, text, time);
    pushMessage(li);
  }

  function addSystem(text) {
    const li = document.createElement('li');
    li.className = 'system';
    li.textContent = text;
    pushMessage(li);
  }

  // video

  function suppress(ms) {
    S.suppressUntil = Date.now() + ms;
  }

  function expectedPosition() {
    const s = S.latest;
    if (!s) return 0;
    return s.playing ? s.position + (Date.now() - s.at) / 1000 : s.position;
  }

  function resetPlayer() {
    suppress(1000);
    el.player.pause();
    el.player.removeAttribute('src');
    el.player.load();
    S.currentSrc = null;
    el.noVideo.hidden = false;
    el.nowPlaying.hidden = true;
  }

  function applyState(msg) {
    const state = msg.state;
    S.latest = { ...state, at: Date.now() };

    if (!state.videoUrl) {
      if (S.currentSrc) resetPlayer();
      return;
    }

    el.noVideo.hidden = true;
    el.nowPlaying.hidden = false;
    el.nowPlaying.textContent = 'Сейчас играет: ' + state.title;

    if (state.videoUrl !== S.currentSrc) {
      suppress(1500);
      S.currentSrc = state.videoUrl;
      el.player.src = state.videoUrl;
      el.player.load();
      el.player.addEventListener('loadedmetadata', () => applyPlayback('SYNC'), { once: true });
      return;
    }

    const isOwnEcho = msg.byId && S.me && msg.byId === S.me.id && ['PLAY', 'PAUSE', 'SEEK'].includes(msg.reason);
    if (isOwnEcho) return;

    applyPlayback(msg.reason);
  }

  function applyPlayback(reason) {
    const s = S.latest;
    if (!s || el.player.readyState < 1) return;
    suppress(700);

    const target = expectedPosition();
    const drift = Math.abs(el.player.currentTime - target);
    const exact = reason === 'SEEK' || reason === 'SYNC' || reason === 'VIDEO';
    if (drift > (exact ? 0.3 : S.config.driftToleranceSeconds)) {
      el.player.currentTime = target;
    }

    if (s.playing && el.player.paused) {
      el.player.play().catch(() => toast('Браузер заблокировал автозапуск. Нажмите ▶ на плеере.', true));
    } else if (!s.playing && !el.player.paused) {
      el.player.pause();
    }
  }

  function userEvent() {
    return Date.now() >= S.suppressUntil && S.latest && S.latest.videoUrl && S.currentSrc;
  }

  el.player.addEventListener('play', () => {
    if (!userEvent()) return;
    if (S.latest.playing && Math.abs(el.player.currentTime - expectedPosition()) < 1.5) return;
    send({ type: 'PLAY', position: el.player.currentTime });
  });

  el.player.addEventListener('pause', () => {
    if (!userEvent()) return;
    if (!S.latest.playing && Math.abs(el.player.currentTime - S.latest.position) < 1.5) return;
    send({ type: 'PAUSE', position: el.player.currentTime });
  });

  el.player.addEventListener('seeked', () => {
    if (!userEvent()) return;
    if (Math.abs(el.player.currentTime - expectedPosition()) < 0.5) return;
    send({ type: 'SEEK', position: el.player.currentTime });
  });

  el.player.addEventListener('error', () => {
    if (S.currentSrc) toast('Не удалось воспроизвести видео. Возможно, формат не поддерживается браузером.', true);
  });

  function seekBy(delta) {
    if (!S.currentSrc || el.player.readyState < 1) {
      toast('Сначала загрузите видео.');
      return;
    }
    const duration = Number.isFinite(el.player.duration) ? el.player.duration : Infinity;
    el.player.currentTime = Math.max(0, Math.min(duration, el.player.currentTime + delta));
  }


  function showLoading(msg) {
    if (!msg.active) {
      hideLoading();
      return;
    }
    el.loading.hidden = false;
    const percent = Math.max(0, Math.min(100, Number(msg.percent) || 0));
    el.loadingText.textContent = percent > 0 && percent < 100 ? msg.message + ': ' + percent + '%' : msg.message;
    el.loadingBar.classList.toggle('indeterminate', percent === 0 || percent === 100);
    el.loadingBar.setAttribute('aria-valuenow', String(percent));
    el.loadingFill.style.width = percent + '%';
  }

  function hideLoading() {
    el.loading.hidden = true;
    el.loadingBar.classList.remove('indeterminate');
  }

  function uploadFile(file) {
    if (!file || !S.me) return;
    const form = new FormData();
    form.append('token', S.me.token);
    form.append('file', file);

    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/rooms/' + encodeURIComponent(S.code) + '/upload');
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) showLoading({ active: true, percent: Math.round((e.loaded / e.total) * 100), message: 'Загружаем файл' });
    };
    xhr.onload = () => {
      hideLoading();
      if (xhr.status < 200 || xhr.status >= 300) {
        let text = 'Не удалось загрузить файл.';
        try { text = JSON.parse(xhr.responseText).message || text; } catch (_) { /* оставляем текст по умолчанию */ }
        toast(text, true);
      }
    };
    xhr.onerror = () => {
      hideLoading();
      toast('Не удалось загрузить файл: нет связи с сервером.', true);
    };
    showLoading({ active: true, percent: 0, message: 'Загружаем файл' });
    xhr.send(form);
  }


  el.username.addEventListener('input', () => {
    el.loginBtn.disabled = el.username.value.trim().length === 0;
    showError(el.loginError, '');
  });

  el.loginForm.addEventListener('submit', (e) => {
    e.preventDefault();
    if (login(el.username.value)) {
      const hashCode = readHashCode();
      if (hashCode) joinRoom(hashCode);
    }
  });

  el.logoutBtn.addEventListener('click', logout);
  el.createBtn.addEventListener('click', createRoom);

  el.joinForm.addEventListener('submit', (e) => {
    e.preventDefault();
    joinRoom(el.roomCodeInput.value);
  });

  el.leaveBtn.addEventListener('click', leaveRoom);

  el.copyCode.addEventListener('click', async () => {
    toast((await copyText(S.code)) ? 'Код ' + S.code + ' скопирован.' : 'Не удалось скопировать. Код: ' + S.code, false);
  });

  el.copyLink.addEventListener('click', async () => {
    const base = (S.config.publicUrl || location.origin).replace(/\/$/, '');
    const link = base + '/#' + S.code;
    toast((await copyText(link)) ? 'Ссылка-приглашение скопирована.' : 'Не удалось скопировать. Ссылка: ' + link, false);
  });

  el.sourceForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const url = el.videoUrl.value.trim();
    if (!url) {
      toast('Введите ссылку на видео или выберите файл.', true);
      return;
    }
    if (send({ type: 'LOAD_VIDEO', url })) el.videoUrl.value = '';
  });

  el.fileInput.addEventListener('change', () => {
    uploadFile(el.fileInput.files[0]);
    el.fileInput.value = '';
  });

  el.backBtn.addEventListener('click', () => seekBy(-10));
  el.fwdBtn.addEventListener('click', () => seekBy(10));
  el.syncBtn.addEventListener('click', () => send({ type: 'SYNC_REQUEST' }));

  el.chatForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const text = el.chatInput.value.trim();
    if (text && send({ type: 'CHAT', text })) el.chatInput.value = '';
  });


  async function init() {
    try {
      S.config = { ...S.config, ...(await api('/api/config')) };
    } catch (_) { /* работаем со значениями по умолчанию */ }

    document.title = S.config.appName;
    document.querySelectorAll('.app-name').forEach((n) => { n.textContent = S.config.appName; });
    el.username.maxLength = S.config.maxNameLength;
    el.roomCodeInput.maxLength = Math.max(S.config.codeLength, 6) + 4;

    const saved = load('wp.username') || '';
    el.username.value = saved;
    el.loginBtn.disabled = saved.trim().length === 0;
    updateControlsAccess();

    const hashCode = readHashCode();
    if (hashCode) {
      el.inviteHint.textContent = 'Вас пригласили в комнату ' + hashCode + '. Введите имя, чтобы войти.';
      el.inviteHint.hidden = false;
      if (saved && login(saved)) joinRoom(hashCode);
    }
  }

  window.addEventListener('beforeunload', () => {
    S.leaving = true;
    if (S.ws) S.ws.close();
  });

  init();
})();
