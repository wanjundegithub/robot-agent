import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const rootDir = fileURLToPath(new URL('../src', import.meta.url))
const suspiciousPattern = /�|闁|闂|缂|婵|濡ゅ|閿\?|锟|鈥/g
const failures = []

function walk(dirPath) {
  for (const entry of readdirSync(dirPath)) {
    const fullPath = join(dirPath, entry)
    const stats = statSync(fullPath)
    if (stats.isDirectory()) {
      walk(fullPath)
      continue
    }
    if (!/\.(ts|tsx|js|jsx|html|css)$/.test(entry)) {
      continue
    }
    const content = readFileSync(fullPath, 'utf8')
    const matches = [...content.matchAll(suspiciousPattern)]
    if (matches.length === 0) {
      continue
    }
    failures.push({
      file: fullPath,
      matches: matches.slice(0, 5).map((match) => match[0]),
    })
  }
}

walk(rootDir)

if (failures.length > 0) {
  console.error('Detected suspicious mojibake-like text in frontend source files:')
  for (const failure of failures) {
    console.error(`- ${failure.file}: ${failure.matches.join(', ')}`)
  }
  process.exit(1)
}

console.log('No suspicious mojibake-like text found in frontend source files.')
