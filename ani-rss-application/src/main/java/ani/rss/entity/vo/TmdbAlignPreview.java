package ani.rss.entity.vo;

import lombok.Data;
import lombok.experimental.Accessors;
import wushuo.tmdb.api.entity.Tmdb;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class TmdbAlignPreview {
    private Boolean canConfirm = false;
    private String blockReason = "";
    private String rssTitle;
    private Double rssEpisode;
    private String rssPubDate;
    private String rssNote;
    private List<TmdbAlignChoice> episodeChoices = new ArrayList<>();
    private Boolean episodeChoiceRequired = false;
    private Integer currentSeason;
    private Integer currentOffset;
    private String renameTemplate;
    private String themoviedbName;
    private Tmdb tmdb;
    private List<EmbyPathChoice> embyChoices = new ArrayList<>();
    private Boolean embyChoiceRequired = false;
    private String embyMessage = "";
}
