package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.entity.Config;
import ani.rss.entity.web.Result;
import ani.rss.service.SubtitleOrcaService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SubtitleTranslateController extends BaseController {
    @Resource private SubtitleOrcaService orca;

    @Auth
    @Operation(summary = "读取字幕翻译执行端当前支持的 Orca 模型")
    @PostMapping("/subtitleTranslateOrcaModels")
    public Result<SubtitleOrcaService.Catalog> models(@RequestBody Config draft) {
        try { return Result.success(orca.models(draft)); }
        catch (IllegalArgumentException error) { return Result.error(error.getMessage()); }
    }
}
