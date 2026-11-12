// Phone Orb -> local background worker.
//
// The floating-ball preset has no file/long-task tools of its own. This module
// adds the same shape the desktop Computer Use preset has: a `background_agent`
// tool that opens one first-class Session running the phone's `phone-background`
// preset, queues the task there, and parks a completion notice on the Orb
// session when that Session goes idle. Status and stop keep the caller's own
// background sessions apart from sessions other Orbs started.
//
// The module lives in dsh-go-bridge because the bridge is already installed at a
// fixed absolute path in the phone rootfs. Its only imports are DSH runtime
// packages, resolved through /opt/dsh-go/node_modules -> ../dsh/node_modules.
import { defineTool } from '@deepseek-ai/dsh-tools'
import { createUserMessage } from '@deepseek-ai/dsh-llm'

export const name = 'tool-background-agent'
export const inject = ['tools', 'sessionController']

const PRESET = 'phone-background'
const NOTICE_MAX = 4000
const ROLE = 'You are the background worker for the phone Orb. You do not talk to the user. '
  + 'Do the task with the workspace, shell, web and the phone tools you have. '
  + 'When you create files the Orb may need, reply with their paths. Keep the final reply short and factual.'
const DESCRIPTION = 'Delegate one stretch of work to this phone\'s background mode. '
  + 'Use it for file work, long shell or web work, or anything that should keep running after the user leaves this chat. '
  + 'A new background session runs the phone-background preset and appears in the phone app under the same workspace. '
  + 'Omit session_id to start a new task; pass the session_id from an earlier result to continue that task. '
  + 'The call returns once the task is queued. A notice arrives later when the background session is idle; then decide again and tell the user the short result. '
  + 'Do not use this for visible screen work in this chat; use the phone tools here.'
const STATUS_DESCRIPTION = 'List the background sessions this Orb chat started, with their task, working directory and running or idle status. '
  + 'Does not include sessions from other Orb chats or the main app.'
const STOP_DESCRIPTION = 'Stop one background session this Orb chat started. '
  + 'It cancels the current turn; the session stays idle so a later background_agent call with the same session_id can continue it.'

/** caller session id -> (background session id -> record) */
const byCaller = new Map()

function callerOf(exec) {
  if (exec.agent === undefined) throw new Error('background_agent requires a calling Orb session')
  return exec.agent
}

function callerMap(callerId) {
  let map = byCaller.get(callerId)
  if (map === undefined) {
    map = new Map()
    byCaller.set(callerId, map)
  }
  return map
}

function lastAssistantText(agent) {
  try {
    const messages = agent.session.deriveMessages()
    for (let index = messages.length - 1; index >= 0; index -= 1) {
      const message = messages[index]
      if (message?.role !== 'assistant') continue
      const text = message.content
        .filter(block => block.type === 'text')
        .map(block => block.text)
        .join('\n')
        .trim()
      if (text !== '') return text
    }
  } catch {
    // A disposed or odd session simply has no final text.
  }
  return undefined
}

/** Whether the queued prompt is still parked in the worker inbox. */
function holdsPrompt(agent, requestId) {
  const parked = [
    ...(agent.inbox?.nextTurn ?? []),
    ...(agent.inbox?.nextStep ?? []),
  ]
  return parked.some(message => (
    message?.source?.kind === 'user'
    && 'rpcId' in message.source
    && message.source.rpcId === requestId
  ))
}

async function waitForIntervalStart(agent, requestId) {
  for (let attempt = 0; attempt < 100 && holdsPrompt(agent, requestId); attempt += 1) {
    await new Promise(resolve => setTimeout(resolve, 100))
  }
}

function noticeText(sessionId, task, outcome, stopped) {
  const prefix = stopped
    ? `Background agent session ${sessionId} was stopped:\n${task}\n\nLast output before it stopped:\n${outcome}`
    : `Background agent session ${sessionId} finished this task:\n${task}\n\n${outcome}`
  const capped = prefix.length <= NOTICE_MAX ? prefix : `${prefix.slice(0, NOTICE_MAX - 1)}…`
  return stopped
    ? `${capped}\n\nDo not restart this task unless the user asks.`
    : capped
}

async function watchCompletion(ctx, owner, worker, sessionId, task, requestId, record) {
  try {
    await waitForIntervalStart(worker, requestId)
    await worker.whenIdle()
    const outcome = lastAssistantText(worker) ?? 'The background session ended without a final assistant message.'
    await owner.whenIdle()
    const agents = ctx.get('agents')
    if (agents?.get(owner.id) !== owner) return
    const text = noticeText(sessionId, task, outcome, record.stopped === true)
    const source = { kind: 'background-agent', form: 'notice', summary: `Background agent ${sessionId} finished` }
    try {
      owner.followup(createUserMessage({ content: [{ type: 'text', text }], source }))
    } catch {
      // Older hosts may reject an unknown source kind; the notice still has to
      // reach the model, so fall back to a user-role message.
      owner.followup(createUserMessage({ content: [{ type: 'text', text }], source: { kind: 'user' } }))
    }
  } catch (error) {
    try {
      ctx.logger?.warn?.(`background_agent: completion watch failed: ${String(error)}`)
    } catch {
      // Disposed contexts may have no logger.
    }
  }
}

function recordTask(callerId, sessionId, record) {
  callerMap(callerId).set(sessionId, record)
}

function parseCreate(result) {
  const sessionId = result?.sessionId
  if (typeof sessionId !== 'string' || sessionId === '') throw new Error('background_agent: session/create returned no id')
  return sessionId
}

export function apply(ctx) {
  ctx.tools.register(defineTool({
    name: 'background_agent',
    description: DESCRIPTION,
    parameters: {
      task: { type: 'string', required: true, description: 'Task for the background session. Be specific about files and the expected result.' },
      session_id: { type: 'string', description: 'Existing background session from an earlier background_agent result, to continue it.' },
      cwd: { type: 'string', description: 'Working directory for a new background session; omit to use this Orb session\'s directory.' },
    },
    output: {
      schema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          accepted: { type: 'boolean', required: true },
          created: { type: 'boolean', required: true },
          session_id: { type: 'string', required: true },
        },
      },
      render: (_args, value) => [{
        type: 'text',
        text: `Background agent ${value.session_id} ${value.created ? 'started' : 'continued'}.`,
      }],
    },
    isConcurrencySafe: () => true,
    presentCall: args => ({
      card: 'generic',
      title: 'Background agent',
      kind: 'execute',
      rawInput: {
        task: args.task,
        ...(args.session_id === undefined ? {} : { session_id: args.session_id }),
        ...(args.cwd === undefined ? {} : { cwd: args.cwd }),
      },
    }),
    async execute(args, exec) {
      const caller = callerOf(exec)
      const task = typeof args.task === 'string' ? args.task.trim() : ''
      if (task === '') throw new Error('background_agent task must include non-whitespace text')
      const requested = typeof args.session_id === 'string' ? args.session_id.trim() : ''
      const controller = ctx.get('sessionController')
      if (controller === undefined) throw new Error('background_agent: session controller is unavailable')

      let sessionId
      let created = false
      let cwd
      if (requested === '') {
        const directory = typeof args.cwd === 'string' && args.cwd.trim() !== ''
          ? args.cwd.trim()
          : caller.session?.header?.cwd
        const result = await controller.create({
          agentPreset: PRESET,
          ...(directory === undefined || directory === '' ? {} : { cwd: directory }),
        })
        sessionId = parseCreate(result)
        created = true
        cwd = directory ?? ''
      } else {
        sessionId = requested
        if (sessionId === caller.id) throw new Error('background_agent cannot target the Orb session itself')
        const known = byCaller.get(caller.id)?.get(sessionId)
        if (known === undefined) {
          throw new Error(`background_agent can continue only a session this Orb chat started, not "${sessionId}"`)
        }
        const inspected = await controller.inspect(sessionId)
        const header = inspected?.meta ?? {}
        if (header.origin === 'subagent') throw new Error(`background_agent cannot continue subagent session "${sessionId}"`)
        if (header.agentPreset !== PRESET) {
          throw new Error(`background_agent can continue only a background session, not "${header.agentPreset ?? 'unknown'}"`)
        }
        cwd = header.cwd ?? known.cwd
      }

      const requestId = `background-agent-${Date.now()}-${Math.random().toString(36).slice(2)}`
      await controller.prompt({
        requestId,
        sessionId,
        mode: 'queue',
        content: [{ type: 'text', text: `${task}\n\n${ROLE}` }],
      }, exec.signal)

      const agents = ctx.get('agents')
      const owner = agents?.get(caller.id) ?? caller
      const worker = agents?.get(sessionId)
      const record = { task, cwd, watch: undefined, stopped: false }
      recordTask(caller.id, sessionId, record)
      if (worker !== undefined) {
        void watchCompletion(ctx, owner, worker, sessionId, task, requestId, record)
      }
      return { accepted: true, created, session_id: sessionId }
    },
  }))

  ctx.tools.register(defineTool({
    name: 'background_agent_status',
    description: STATUS_DESCRIPTION,
    parameters: {},
    output: {
      schema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          count: { type: 'integer', required: true },
          tasks: {
            type: 'array',
            required: true,
            items: {
              type: 'object',
              additionalProperties: false,
              properties: {
                session_id: { type: 'string', required: true },
                task: { type: 'string', required: true },
                cwd: { type: 'string', required: true },
                status: { type: 'string', required: true },
              },
            },
          },
        },
      },
      render: (_args, value) => [{
        type: 'text',
        text: value.count === 0
          ? 'No background sessions from this Orb chat.'
          : value.tasks.map(entry => `${entry.session_id}: ${entry.status} — ${entry.task} (${entry.cwd})`).join('\n'),
      }],
    },
    isConcurrencySafe: () => true,
    presentCall: () => ({ card: 'generic', title: 'Background agent status', kind: 'read' }),
    async execute(_args, exec) {
      const caller = callerOf(exec)
      const agents = ctx.get('agents')
      const tasks = [...(byCaller.get(caller.id) ?? [])].map(([sessionId, entry]) => ({
        session_id: sessionId,
        task: entry.task,
        cwd: entry.cwd,
        status: agents?.get(sessionId)?.status === 'running' ? 'running' : 'idle',
      }))
      return { count: tasks.length, tasks }
    },
  }))

  ctx.tools.register(defineTool({
    name: 'background_agent_stop',
    description: STOP_DESCRIPTION,
    parameters: {
      session_id: { type: 'string', required: true, description: 'Background session this Orb chat started.' },
    },
    output: {
      schema: {
        type: 'object',
        additionalProperties: false,
        properties: {
          accepted: { type: 'boolean', required: true },
          session_id: { type: 'string', required: true },
        },
      },
      render: (_args, value) => [{
        type: 'text',
        text: `Stopped background agent session ${value.session_id}. The session stays idle.`,
      }],
    },
    isConcurrencySafe: () => true,
    presentCall: args => ({
      card: 'generic',
      title: 'Stop background agent',
      kind: 'execute',
      rawInput: { session_id: args.session_id },
    }),
    async execute(args, exec) {
      const caller = callerOf(exec)
      const sessionId = typeof args.session_id === 'string' ? args.session_id.trim() : ''
      if (sessionId === '') throw new Error('background_agent_stop session_id must be a non-empty id')
      const known = byCaller.get(caller.id)?.get(sessionId)
      if (known === undefined) {
        throw new Error(`background_agent_stop can stop only a session this Orb chat started, not "${sessionId}"`)
      }
      known.stopped = true
      ctx.get('agents')?.get(sessionId)?.cancel({ kind: 'user' })
      return { accepted: true, session_id: sessionId }
    },
  }))
}