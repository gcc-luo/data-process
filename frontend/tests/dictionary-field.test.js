import { test } from 'node:test'
import assert from 'node:assert/strict'
import { dictionaryField } from '../src/components/dataobject/dictionary-field.js'

test('maps dictionary attributes and preserves comments per database', () => {
  const entry={code:'customer_name',name:'客户名称',meaning:'客户姓名',dataType:'varchar',length:30,precision:null}
  for(const [source,type] of [['MYSQL','VARCHAR'],['POSTGRESQL','VARCHAR'],['ORACLE','VARCHAR2']]) {
    const result=dictionaryField(entry,source)
    assert.deepEqual(result.field,{name:'customer_name',type,length:30,scale:null,nullable:true,primaryKey:false,remarks:'客户姓名'})
    assert.equal(result.needsLength,false)
  }
})
test('maps numeric types and flags incomplete or unsupported definitions',()=>{
  assert.deepEqual(dictionaryField({code:'id',dataType:'integer'},'ORACLE').field,{name:'id',type:'NUMBER',length:10,scale:0,nullable:true,primaryKey:false,remarks:''})
  assert.equal(dictionaryField({code:'amount',dataType:'decimal',length:12,precision:2},'POSTGRESQL').field.type,'NUMERIC')
  assert.equal(dictionaryField({code:'name',dataType:'varchar',length:null},'MYSQL').needsLength,true)
  assert.match(dictionaryField({code:'active',dataType:'boolean'},'MYSQL').error,/不能安全映射/)
})
