/** Geographic rendering input. Business aggregation and geocoding belong to the caller. */
export interface CityDistributionPoint {
  name: string
  value: number
  /** WGS84 longitude and latitude. Omit when the city has not been reliably located. */
  coordinate?: readonly [number, number] | null
}

export interface ValidCityDistributionPoint extends CityDistributionPoint {
  coordinate?: readonly [number, number]
}

/**
 * Reference-only values from Yunshu BigScreenView.vue SALES_CITIES.
 * Only pass these with isExample=true and an explicit example source/metric label.
 * City coordinates are rendering anchors, never customer addresses or MES measurements.
 */
export const YUNSHU_EXAMPLE_CITIES: readonly CityDistributionPoint[] = [
  { name: '广州市', value: 9300, coordinate: [113.2644, 23.1291] },
  { name: '青岛市', value: 11200, coordinate: [120.3826, 36.0671] },
  { name: '太原市', value: 8900, coordinate: [112.5489, 37.8706] },
  { name: '天津市', value: 8100, coordinate: [117.2009, 39.0842] },
  { name: '兰州市', value: 7400, coordinate: [103.8343, 36.0611] },
  { name: '杭州市', value: 5600, coordinate: [120.1551, 30.2741] },
  { name: '沈阳市', value: 4300, coordinate: [123.4315, 41.8057] },
  { name: '重庆市', value: 4000, coordinate: [106.5516, 29.563] },
  { name: '拉萨', value: 3900, coordinate: [91.1409, 29.6456] },
  { name: '乌鲁木齐', value: 3600, coordinate: [87.6168, 43.8256] },
  { name: '长沙市', value: 1800, coordinate: [112.9388, 28.2282] },
  { name: '厦门市', value: 2400, coordinate: [118.0894, 24.4798] },
  { name: '福州市', value: 1900, coordinate: [119.2965, 26.0745] },
  { name: '上海市', value: 1700, coordinate: [121.4737, 31.2304] },
  { name: '深圳市', value: 1400, coordinate: [114.0579, 22.5431] },
  { name: '贵阳市', value: 800, coordinate: [106.6302, 26.6477] },
  { name: '北京市', value: 400, coordinate: [116.4074, 39.9042] },
  { name: '香港', value: 1500, coordinate: [114.171, 22.3196] }
]

export const isValidCoordinate = (coordinate: unknown): coordinate is readonly [number, number] =>
  Array.isArray(coordinate) &&
  coordinate.length === 2 &&
  coordinate.every((value) => typeof value === 'number' && Number.isFinite(value)) &&
  Math.abs(coordinate[0]) <= 180 &&
  Math.abs(coordinate[1]) <= 90

/** Invalid values never enter either chart; missing coordinates still allow a truthful ranking. */
export const normalizeCityDistribution = (
  points: readonly CityDistributionPoint[]
): ValidCityDistributionPoint[] =>
  points.flatMap((point) => {
    if (
      !point ||
      typeof point.name !== 'string' ||
      !point.name.trim() ||
      typeof point.value !== 'number' ||
      !Number.isFinite(point.value) ||
      point.value < 0
    ) {
      return []
    }
    return [
      {
        name: point.name.trim(),
        value: point.value,
        coordinate: isValidCoordinate(point.coordinate) ? point.coordinate : undefined
      }
    ]
  })

export const escapeChartText = (value: unknown): string =>
  String(value ?? '').replace(/[&<>"']/g, (character) => {
    const entities: Record<string, string> = {
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      '"': '&quot;',
      "'": '&#39;'
    }
    return entities[character]
  })
