import React, { useMemo, useState } from 'react'
import type { FormDefinition, FormField } from '../types'

interface FormDialogProps {
  form: FormDefinition
  onSubmit: (data: Record<string, unknown>) => void
  onClose: () => void
  initialValues?: Record<string, unknown>
}

const FormDialog: React.FC<FormDialogProps> = ({ form, onSubmit, onClose, initialValues }) => {
  const initial = useMemo(() => {
    const values: Record<string, unknown> = {}
    form.fields.forEach((field) => {
      values[field.name] = initialValues?.[field.name] ?? ''
    })
    return values
  }, [form.fields, initialValues])

  const [values, setValues] = useState<Record<string, unknown>>(initial)

  const updateField = (field: FormField, value: string) => {
    const castValue =
      field.type === 'number' ? (value === '' ? '' : Number(value)) : value
    setValues((prev) => ({ ...prev, [field.name]: castValue }))
  }

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault()
    onSubmit(values)
  }

  return (
    <div className="form-overlay">
      <div className="form-card">
        <div className="form-header">
          <div>
            <div className="panel-title">{form.title || '请补充信息'}</div>
            {form.description && <div className="text-sm text-slate-500">{form.description}</div>}
          </div>
          <button onClick={onClose} className="text-slate-400 hover:text-slate-600">关闭</button>
        </div>
        <form onSubmit={handleSubmit} className="space-y-4">
          {form.fields.map((field) => (
            <div key={field.name} className="space-y-1">
              <label className="text-sm font-medium text-slate-700">
                {field.label || field.name}
                {field.required && <span className="text-rose-500 ml-1">*</span>}
              </label>
              {field.type === 'textarea' ? (
                <textarea
                  className="w-full rounded-lg border border-slate-200 px-3 py-2 focus:outline-none focus:ring-2 focus:ring-cyan-500"
                  placeholder={field.placeholder}
                  value={String(values[field.name] ?? '')}
                  onChange={(e) => updateField(field, e.target.value)}
                  rows={3}
                />
              ) : field.type === 'select' ? (
                <select
                  className="w-full rounded-lg border border-slate-200 px-3 py-2 focus:outline-none focus:ring-2 focus:ring-cyan-500"
                  value={String(values[field.name] ?? '')}
                  onChange={(e) => updateField(field, e.target.value)}
                >
                  <option value="">请选择</option>
                  {(field.options || []).map((option) => (
                    <option key={option} value={option}>{option}</option>
                  ))}
                </select>
              ) : (
                <input
                  type={field.type === 'number' ? 'number' : field.type}
                  className="w-full rounded-lg border border-slate-200 px-3 py-2 focus:outline-none focus:ring-2 focus:ring-cyan-500"
                  placeholder={field.placeholder}
                  value={String(values[field.name] ?? '')}
                  onChange={(e) => updateField(field, e.target.value)}
                />
              )}
            </div>
          ))}
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 text-sm text-slate-600 hover:text-slate-800"
            >
              稍后填写
            </button>
            <button
              type="submit"
              className="px-5 py-2 text-sm font-semibold text-white bg-slate-900 rounded-lg hover:bg-slate-800"
            >
              提交
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

export default FormDialog
