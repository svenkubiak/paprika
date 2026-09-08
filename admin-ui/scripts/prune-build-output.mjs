import { rm } from 'node:fs/promises'
import { resolve } from 'node:path'

const assetsDir = resolve(import.meta.dirname, '../../src/main/resources/files/assets')

await rm(resolve(assetsDir, 'admin'), { recursive: true, force: true })
