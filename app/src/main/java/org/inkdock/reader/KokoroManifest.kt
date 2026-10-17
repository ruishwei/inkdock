package org.inkdock.reader

/** Fixed upstream revision; per-file SHA256 verified against official release digest. */
object KokoroManifest {
    const val RUNTIME_COMMIT = "0586cd8e14d7eae56e52ae0f1fd7b947e74b84f7"
    const val DOWNLOAD_BYTES = 170433930L
    val speakers = mapOf(3 to "zf_001", 4 to "zf_002", 5 to "zf_003", 6 to "zf_004", 7 to "zf_005", 8 to "zf_006", 9 to "zf_007", 10 to "zf_008", 11 to "zf_017", 12 to "zf_018", 13 to "zf_019", 14 to "zf_021", 15 to "zf_022", 16 to "zf_023", 17 to "zf_024", 18 to "zf_026", 19 to "zf_027", 20 to "zf_028", 21 to "zf_032", 22 to "zf_036", 23 to "zf_038", 24 to "zf_039", 25 to "zf_040", 26 to "zf_042", 27 to "zf_043", 28 to "zf_044", 29 to "zf_046", 30 to "zf_047", 31 to "zf_048", 32 to "zf_049", 33 to "zf_051", 34 to "zf_059", 35 to "zf_060", 36 to "zf_067", 37 to "zf_070", 38 to "zf_071", 39 to "zf_072", 40 to "zf_073", 41 to "zf_074", 42 to "zf_075", 43 to "zf_076", 44 to "zf_077", 45 to "zf_078", 46 to "zf_079", 47 to "zf_083", 48 to "zf_084", 49 to "zf_085", 50 to "zf_086", 51 to "zf_087", 52 to "zf_088", 53 to "zf_090", 54 to "zf_092", 55 to "zf_093", 56 to "zf_094", 57 to "zf_099", 58 to "zm_009", 59 to "zm_010", 60 to "zm_011", 61 to "zm_012", 62 to "zm_013", 63 to "zm_014", 64 to "zm_015", 65 to "zm_016", 66 to "zm_020", 67 to "zm_025", 68 to "zm_029", 69 to "zm_030", 70 to "zm_031", 71 to "zm_033", 72 to "zm_034", 73 to "zm_035", 74 to "zm_037", 75 to "zm_041", 76 to "zm_045", 77 to "zm_050", 78 to "zm_052", 79 to "zm_053", 80 to "zm_054", 81 to "zm_055", 82 to "zm_056", 83 to "zm_057", 84 to "zm_058", 85 to "zm_061", 86 to "zm_062", 87 to "zm_063", 88 to "zm_064", 89 to "zm_065", 90 to "zm_066", 91 to "zm_068", 92 to "zm_069", 93 to "zm_080", 94 to "zm_081", 95 to "zm_082", 96 to "zm_089", 97 to "zm_091", 98 to "zm_095", 99 to "zm_096", 100 to "zm_097", 101 to "zm_098", 102 to "zm_100")
    val int8Files = listOf(
        KokoroModelStore.ModelFile("model.int8.onnx", "https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_1/resolve/155831f1b4ba23b1f5c058be6a61df90cefb2a37/model.int8.onnx", 114299010L, "bda15858163726a492d02a9a727bc263551b86ac77f90812c4b30ff41d380e26"),
        KokoroModelStore.ModelFile("voices.bin", "https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_1/resolve/155831f1b4ba23b1f5c058be6a61df90cefb2a37/voices.bin", 53790720L, "e64a5a581d8c2a350d848f51c3121657cd83aa07ed6109172177345874a7244c"),
        KokoroModelStore.ModelFile("lexicon-zh.txt", "https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_1/resolve/155831f1b4ba23b1f5c058be6a61df90cefb2a37/lexicon-zh.txt", 2119465L, "11111d8cd695fba2ace1367a1d0a708b586e6ef5c1f9be91da5d7eef129b651c"),
        KokoroModelStore.ModelFile("tokens.txt", "https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_1/resolve/155831f1b4ba23b1f5c058be6a61df90cefb2a37/tokens.txt", 1111L, "931ab2df2400cd65d580a22402024c2347ced8ae9ea300e545144b1aacc48e14"),
        KokoroModelStore.ModelFile("number-zh.fst", "https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_1/resolve/155831f1b4ba23b1f5c058be6a61df90cefb2a37/number-zh.fst", 64482L, "743f402181fcfebf76cc2f0546b71fa26476e626fbe4e460fb7b4c3a7a8bd5bd"),
        KokoroModelStore.ModelFile("date-zh.fst", "https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_1/resolve/155831f1b4ba23b1f5c058be6a61df90cefb2a37/date-zh.fst", 59154L, "eb8aa079ae3cb81d8f4404992f39d61a0cb990947512b5b8d1e54d1f6980e718"),
        KokoroModelStore.ModelFile("phone-zh.fst", "https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_1/resolve/155831f1b4ba23b1f5c058be6a61df90cefb2a37/phone-zh.fst", 88630L, "1ac2b6fa56b1442320c4de7db08353bab8963a2b57f365eebcdd3a2d3562f8d7"),
        KokoroModelStore.ModelFile("LICENSE", "https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_1/resolve/155831f1b4ba23b1f5c058be6a61df90cefb2a37/LICENSE", 11358L, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30")
    )

    data class Variant(val id:String,val label:String,val modelName:String,val files:List<KokoroModelStore.ModelFile>) {
        val bytes get()=files.sumOf{it.size}
    }
    val int8 = Variant("int8","省空间 int8 · 170.4 MB","model.int8.onnx",int8Files)
    val float = Variant("float","高音质 float（优先）· 381.8 MB","model.onnx",listOf(
        KokoroModelStore.ModelFile("model.onnx","https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_1/resolve/914313412b607d95400bcd12446233fbd1248801/model.onnx",325631784L,"acc4adc175b9d9986106cd20060329673ad5a2e12ef3c557d2d3745b694f8b38")
    ) + int8Files.drop(1))
    val variants = listOf(float,int8)
    fun variant(id:String)=variants.firstOrNull{it.id==id} ?: float
    val files get()=float.files
}
