import type {Catalog} from '../translate'

/**
 * Bản dịch tiếng Việt: gộp mọi tệp `*.ts` nằm cạnh tệp này.
 *
 * <p>Khóa nào thiếu ở đây sẽ hiện ra chính khóa đó trên màn hình, cố ý xấu để nhìn là thấy
 * ngay và grep được - khác với việc trả về chuỗi rỗng, vốn trông như lỗi bố cục và khiến
 * người ta đi tìm nhầm chỗ.
 */
const modules = import.meta.glob<{messages: Catalog}>('./*.ts', {eager: true})

export const catalog: Catalog = Object.entries(modules)
  .filter(([path]) => !path.endsWith('/index.ts'))
  .reduce<Catalog>((all, [, module]) => Object.assign(all, module.messages), {})
