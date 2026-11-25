package com.example.governance.dictionary;

import com.example.governance.security.AuditLog;
import java.io.ByteArrayOutputStream;
import java.util.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/dictionary")
public class DictionaryController {
    private static final String[] HEADERS={"目录路径","字典标识","字典名","字典含义","类型","长度","精度","描述"};
    record DirectoryInput(String name, Long parentId) {}
    record SortRequest(List<com.example.governance.common.DirectorySorter.Item> items) {}
    record ImportError(int row,String reason) {}
    record ImportResult(int success,int failed,List<ImportError> errors) {}
    private final DictionaryStore store; private final AuditLog audit;
    public DictionaryController(DictionaryStore store,AuditLog audit){this.store=store;this.audit=audit;}
    @GetMapping("/directories") public List<DictionaryStore.Directory> directories(){return store.directories();}
    @GetMapping("/entries") public DictionaryStore.Page entries(@RequestParam(required=false) Long directoryId,@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size){return store.page(directoryId,search,page,size);}
    @PostMapping("/directories") public Map<String,Long> createDirectory(@RequestBody DirectoryInput input,Authentication auth){long id=store.createDirectory(input.name(),input.parentId());audit.success(auth,"dictionary.directory.create","dictionary_directory",id);return Map.of("id",id);}
    @PostMapping("/directories/sort") public void sortDirectories(@RequestBody SortRequest input,Authentication auth){try{store.sortDirectories(input.items());audit.success(auth,"dictionary.directory.sort","dictionary_directory","tree");}catch(IllegalArgumentException|IllegalStateException e){audit.failure(auth,"dictionary.directory.sort","dictionary_directory","tree",e.getMessage());throw e;}}
    @PutMapping("/directories/{id}") public void renameDirectory(@PathVariable long id,@RequestBody DirectoryInput input,Authentication auth){store.renameDirectory(id,input.name());audit.success(auth,"dictionary.directory.update","dictionary_directory",id);}
    @DeleteMapping("/directories/{id}") public void deleteDirectory(@PathVariable long id,Authentication auth){store.deleteDirectory(id);audit.success(auth,"dictionary.directory.delete","dictionary_directory",id);}
    @PostMapping("/entries") public Map<String,Long> create(@RequestBody DictionaryStore.Input input,Authentication auth){long id=store.create(input);audit.success(auth,"dictionary.create","dictionary",id);return Map.of("id",id);}
    @PutMapping("/entries/{id}") public void update(@PathVariable long id,@RequestBody DictionaryStore.Input input,Authentication auth){store.update(id,input);audit.success(auth,"dictionary.update","dictionary",id);}
    @DeleteMapping("/entries/{id}") public void delete(@PathVariable long id,Authentication auth){store.delete(id);audit.success(auth,"dictionary.delete","dictionary",id);}
    @GetMapping("/template") public ResponseEntity<byte[]> template() throws Exception {
        try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()){
            var sheet=workbook.createSheet("数据字典");var header=sheet.createRow(0);
            for(int i=0;i<HEADERS.length;i++){header.createCell(i).setCellValue(HEADERS[i]);sheet.setColumnWidth(i, i==7?12000:6000);}
            sheet.createFreezePane(0,1);
            var guide=workbook.createSheet("填写说明与示例");guide.setColumnWidth(0,6000);guide.setColumnWidth(1,18000);
            String[][] instructions={
                {"使用说明","请在第一个工作表“数据字典”中填写，从第 2 行开始每行填写一条。这里的示例不会导入。"},
                {"目录路径（必填）","填写系统中已存在的目录全路径，使用英文斜杠 / 分隔，例如 基础数据/用户信息；需先在系统创建对应目录。"},
                {"字典标识（必填）","全局唯一，以英文字母或下划线开头，仅可包含英文字母、数字和下划线，最多 100 字符，例如 user_status。"},
                {"字典名、字典含义（必填）","填写便于理解的名称和含义，最多各 200 字符。"},
                {"类型（必填）","填写下面列出的英文类型名称，例如 varchar 或 integer；不是填写 1、2、3 等类型编号。"},
                {"长度（选填）","填写正整数，例如 20；可留空。"},
                {"精度（选填）","仅 decimal 或 double 可填，填写不小于 0 的整数，且须同时填写长度，精度不能大于长度。其他类型留空。"},
                {"描述（选填）","填写补充说明，最多 1000 字符。"},
                {"导入限制","仅支持 .xlsx、文件不超过 5 MB、数据不超过 5000 行；重复标识或错误行会跳过并报告。"},
                {"可选类型","varchar（可变长度文本）、char（固定长度文本）、integer（整数）、bigint（大整数）、decimal（定点小数）、double（浮点数）、date（日期）、datetime（日期时间）、boolean（布尔值）。"},
                {"注意","本模板定义字段的类型；1、2、3 或文字等具体业务值并不是此模板的“类型”填写内容。"}
            };
            var wrap=workbook.createCellStyle();wrap.setWrapText(true);wrap.setVerticalAlignment(VerticalAlignment.TOP);
            for(int i=0;i<instructions.length;i++){var r=guide.createRow(i);r.createCell(0).setCellValue(instructions[i][0]);var cell=r.createCell(1);cell.setCellValue(instructions[i][1]);cell.setCellStyle(wrap);r.setHeightInPoints(i==9?45:30);}
            int start=instructions.length+2;var exampleTitle=guide.createRow(start);exampleTitle.createCell(0).setCellValue("填写示例（仅供参考，不会导入）");var exampleHeader=guide.createRow(start+1);for(int i=0;i<HEADERS.length;i++)exampleHeader.createCell(i).setCellValue(HEADERS[i]);
            String[][] examples={{"基础数据/用户信息","user_name","用户名称","用户显示名称","varchar","50","","文本字段"},{"基础数据/用户信息","login_count","登录次数","累计登录次数","integer","","","整数类型，长度可留空"},{"基础数据/用户信息","account_balance","账户余额","账户金额","decimal","10","2","小数类型，长度 10、精度 2"},{"基础数据/用户信息","is_active","是否启用","账号是否启用","boolean","","","布尔类型"}};
            for(int i=0;i<examples.length;i++){var r=guide.createRow(start+2+i);for(int j=0;j<examples[i].length;j++)r.createCell(j).setCellValue(examples[i][j]);}
            for(int i=2;i<HEADERS.length;i++)guide.setColumnWidth(i,6000);
            workbook.write(output);return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=dictionary-template.xlsx").contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(output.toByteArray());
        }
    }
    @PostMapping("/import") public ImportResult importFile(@RequestParam("file") MultipartFile file,Authentication auth){
        if(file.isEmpty()||file.getSize()>5*1024*1024||file.getOriginalFilename()==null||!file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".xlsx"))throw new IllegalArgumentException("请选择不超过 5 MB 的 xlsx 文件");
        List<ImportError> errors=new ArrayList<>();int success=0;
        try(var workbook=new XSSFWorkbook(file.getInputStream())){if(workbook.getNumberOfSheets()==0)throw new IllegalArgumentException("文件为空");var sheet=workbook.getSheetAt(0);if(sheet.getLastRowNum()>5000)throw new IllegalArgumentException("最多导入 5000 行");var format=new DataFormatter();var header=sheet.getRow(0);if(header==null)throw new IllegalArgumentException("缺少表头");for(int i=0;i<HEADERS.length;i++)if(!HEADERS[i].equals(format.formatCellValue(header.getCell(i)).trim()))throw new IllegalArgumentException("表头与模板不匹配");if(sheet.getLastRowNum()<1)throw new IllegalArgumentException("文件没有数据");
            for(int i=1;i<=sheet.getLastRowNum();i++){var row=sheet.getRow(i);try{if(row==null)throw new IllegalArgumentException("空行");String[] v=new String[8];for(int c=0;c<8;c++)v[c]=format.formatCellValue(row.getCell(c)).trim();Long directory=store.directoryByPath(v[0]);Integer length=number(v[5]), precision=number(v[6]);store.create(new DictionaryStore.Input(directory,v[1],v[2],v[3],v[4],length,precision,v[7]));success++;}catch(Exception ex){errors.add(new ImportError(i+1,ex instanceof IllegalArgumentException?ex.getMessage():"写入失败"));}}
        }catch(IllegalArgumentException ex){throw ex;}catch(Exception ex){throw new IllegalArgumentException("无法解析 Excel 文件");}
        audit.success(auth,"dictionary.import","dictionary","import","成功 "+success+" 条，失败 "+errors.size()+" 条");return new ImportResult(success,errors.size(),errors);
    }
    @ExceptionHandler(IllegalStateException.class) public ResponseEntity<Map<String,String>> conflict(IllegalStateException e){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error",e.getMessage()));}
    private Integer number(String s){if(s.isEmpty())return null;try{return Integer.valueOf(s);}catch(NumberFormatException e){throw new IllegalArgumentException("长度或精度必须为整数");}}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e, jakarta.servlet.http.HttpServletRequest request, Authentication auth){String method=request.getMethod(),path=request.getRequestURI();if(auth!=null && !"GET".equals(method) && !path.endsWith("/directories/sort")){String operation="POST".equals(method)?"create":"PUT".equals(method)?"update":"delete";String action=path.endsWith("/import")?"dictionary.import":path.contains("/directories")?"dictionary.directory."+operation:"dictionary."+operation;String objectType=path.contains("/directories")?"dictionary_directory":"dictionary";audit.failure(auth,action,objectType,"request",e.getMessage());}return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
}
