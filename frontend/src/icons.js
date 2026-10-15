import { defineComponent, h, useId } from 'vue'

// These assets are derived only from the user-provided HarmonyOS_Icons folder.
const assets = import.meta.glob('./assets/harmony/*.svg', { query: '?raw', import: 'default', eager: true })
function harmonyIcon(name) {
  const raw = assets[`./assets/harmony/${name}.svg`]
  if (!raw) throw new Error(`Missing HarmonyOS icon: ${name}`)
  const body = raw.slice(raw.indexOf('>') + 1, raw.lastIndexOf('</svg>'))
  return defineComponent({
    name: `Harmony${name}`,
    inheritAttrs: false,
    props: { size: { type: [Number, String], default: 24 }, color: { type: String, default: 'currentColor' } },
    setup(props, { attrs }) {
      // Sketch exports reuse path/mask IDs. Prefix every reference per component instance.
      const prefix = `harmony-${useId().replace(/[^a-zA-Z0-9_-]/g, '-')}-`
      const ids = [...body.matchAll(/\bid="([^"]+)"/g)].map(match => match[1])
      let content = body
      for (const id of ids) {
        content = content.replaceAll(`id="${id}"`, `id="${prefix}${id}"`)
          .replaceAll(`href="#${id}"`, `href="#${prefix}${id}"`)
          .replaceAll(`url(#${id})`, `url(#${prefix}${id})`)
      }
      return () => h('svg', {
        ...attrs, xmlns: 'http://www.w3.org/2000/svg', 'xmlns:xlink': 'http://www.w3.org/1999/xlink',
        width: props.size, height: props.size, viewBox: '0 0 24 24', color: props.color,
        class: ['harmony-icon', attrs.class], 'aria-hidden': attrs['aria-label'] ? undefined : 'true',
        focusable: 'false', 'data-harmony-icon': name, innerHTML: content,
      })
    },
  })
}

export const ArrowLeft = harmonyIcon('ArrowLeft')
export const ArrowRight = harmonyIcon('ArrowRight')
export const Bell = harmonyIcon('Bell')
export const Bot = harmonyIcon('Bot')
export const CalendarDays = harmonyIcon('CalendarDays')
export const CalendarPlus = harmonyIcon('CalendarPlus')
export const CheckCircle2 = harmonyIcon('CheckCircle2')
export const ChevronDown = harmonyIcon('ChevronDown')
export const CircleHelp = harmonyIcon('CircleHelp')
export const ClipboardCheck = harmonyIcon('ClipboardCheck')
export const ClipboardList = harmonyIcon('ClipboardList')
export const Copy = harmonyIcon('Copy')
export const Download = harmonyIcon('Download')
export const ImagePlus = harmonyIcon('ImagePlus')
export const LayoutDashboard = harmonyIcon('LayoutDashboard')
export const LogOut = harmonyIcon('LogOut')
export const MapPin = harmonyIcon('MapPin')
export const Megaphone = harmonyIcon('Megaphone')
export const QrCode = harmonyIcon('QrCode')
export const RefreshCw = harmonyIcon('RefreshCw')
export const Save = harmonyIcon('Save')
export const ScrollText = harmonyIcon('ScrollText')
export const ShieldCheck = harmonyIcon('ShieldCheck')
export const TicketCheck = harmonyIcon('TicketCheck')
export const Trash2 = harmonyIcon('Trash2')
export const Unlink2 = harmonyIcon('Unlink2')
export const Users = harmonyIcon('Users')
export const UsersRound = harmonyIcon('UsersRound')
export const Search = harmonyIcon('Search')
export const Close = harmonyIcon('Close')
