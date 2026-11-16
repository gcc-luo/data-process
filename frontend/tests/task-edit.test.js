import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { formFromTask, configurationFromForm, emptyTask, newParameter } from '../src/task-edit.js'
test('editing a task preserves unexposed configuration while changing SQL', () => {
  const original = { id: 2, name: '清理', type: 'SQL', configuration: JSON.stringify({ sourceId: 1, sql: 'delete from x', parameters: [], extension: { setting: true } }) }
  const form = formFromTask(original); form.sql = 'delete from y'
  assert.deepEqual(configurationFromForm(form), { sourceId: 1, sql: 'delete from y', parameters: [], extension: { setting: true } })
})
test('editing refuses parameter lists the form cannot safely represent', () => {
  assert.throws(() => formFromTask({ type: 'SQL', configuration: '{"sourceId":1,"sql":"select ?","parameters":[{"type":"VALUE","value":{"x":1}}]}' }), /参数/)
  assert.throws(() => formFromTask({ type: 'SQL', configuration: '{"sourceId":1,"sql":"select ?","parameters":[{"type":"VALUE","value":9007199254740993}]}' }), /参数/)
})
test('SQL parameters retain order, types, null and business dates when editing', () => {
  const parameters = [{type:'VALUE',value:'x'},{type:'VALUE',value:3.5},{type:'VALUE',value:false},{type:'VALUE',value:null},{type:'BUSINESS_DATE'}]
  const form = formFromTask({ type: 'SQL', name:'n', configuration: JSON.stringify({sourceId:1, sql:'update x set a=?', parameters, custom:{retain:true}}) })
  const config = configurationFromForm(form)
  assert.deepEqual(config.parameters, parameters); assert.deepEqual(config.custom, {retain:true})
})
test('sync source parameter edits preserve SQL binding order', () => {
  const params = [{type:'BUSINESS_DATE'}, {type:'VALUE',value:0}, {type:'VALUE',value:null}]
  const form = formFromTask({type:'SYNC',name:'x',configuration:JSON.stringify({sourceId:1,targetId:2,sourceQuery:'select 1',sourceParameters:params,targetTable:'t',targetColumns:['id']})})
  form.parameters.reverse(); assert.deepEqual(configurationFromForm(form).sourceParameters, [...params].reverse())
})
test('sync task preserves ordered columns and retry settings', () => {
  const form = formFromTask({ name: '同步', type: 'SYNC', configuration: JSON.stringify({ sourceId: 1, targetId: 2, sourceQuery: 'select id, name from x', sourceParameters: [{type:'BUSINESS_DATE'}], targetTable:'t', targetColumns:['id','name'], writeMode:'UPSERT', keyColumns:['id'], batchSize:20, retry:{safe:true,maxRetries:2,delaySeconds:45} }) })
  const config = configurationFromForm(form)
  assert.deepEqual(config.targetColumns, ['id','name']); assert.deepEqual(config.sourceParameters, [{type:'BUSINESS_DATE'}]); assert.equal(config.retry.maxRetries, 2)
})

test('numeric input is rejected when JSON number precision cannot be preserved', () => {
  const form = emptyTask(); form.parameters = [{kind:'number', value:'1.234567890123456789'}]
  assert.throws(() => configurationFromForm(form), /参数/)
  assert.throws(() => formFromTask({type:'SQL', name:'x', configuration:'{"sourceId":1,"sql":"select ?","parameters":[{"type":"VALUE","value":1.234567890123456789}]}'}), /参数/)
  assert.throws(() => formFromTask({type:'SQL', name:'x', configuration:'{"sourceId":1,"sql":"select ?","parameters":[{"type":"VALUE","value":1.0000000000000001}]}'}), /参数/)
  form.parameters = [{kind:'number', value:'1e-400'}]; assert.throws(() => configurationFromForm(form), /参数/)
  assert.equal(newParameter('number').value,'0')
})
test('editing preserves extension properties on enabled retry policy', () => {
  const form = formFromTask({type:'SQL',name:'x',configuration:JSON.stringify({sourceId:1,sql:'select 1',parameters:[],retry:{safe:true,maxRetries:1,delaySeconds:5,extension:'keep'}})})
  assert.equal(configurationFromForm(form).retry.extension,'keep')
})
